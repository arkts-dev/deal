/*
 * deal/native/host.c — the `host` service group implementation.
 *
 * The native host load (`llvm-native-host-abi-and-async` H1, H7), the
 * synchronous crossing (`HostCallSync`, H2/H4), the host value surface (H2 with
 * the semantics of `llvm-native-runtime-value-model` R11), the handle table
 * (H3) and the DEAL-origin failure-token table (H2). Async intake, delivery and
 * the pump belong to the `async` group.
 *
 * Value carriers (H2, R11). One `deal_value` is a slot/generation reference into
 * the value table the `value` group owns; this group decides what one entry
 * means:
 *
 *   - a *view* names a managed DEAL object (or one of the two nullish
 *     singletons), so a host read observes DEAL state and a host store through
 *     the view is validated against the declared slot and barriered (M2);
 *   - a *boxed carrier* is crossing-scoped platform storage this group owns: a
 *     host-built scalar, string, array, record, class instance, function value
 *     or one of the closed non-data presentations (absent, nothing, async
 *     operation, invalid Unicode). Boxes hold slot references to one another,
 *     so a boxed graph is exactly the host's own composition order; the declared
 *     position belongs to the crossing, never to construction.
 *
 * Materialization (R11). At the return crossing the runtime classifies one
 * carrier, materializes a boxed graph into the managed representation with alias
 * identity and self-reference preserved (one memoized conversion per boxed
 * node), validates the result at the declared descriptor through the one
 * `deal_check_value` entry and admits it. The host object is never mutated. A
 * view passes through unchanged, so a DEAL-origin value keeps its identity.
 *
 * Failure channel (H2, R5). A host-authored failure carries its own text and
 * becomes a builtin Error with no origin, frames or cause. A DEAL-origin failure
 * token names one `deal_error *` for one crossing and is single use: the host
 * relays it (the identical Error is re-admitted) or discards it. A crossing that
 * ends with an unconsumed token is a host protocol violation — one deterministic
 * stderr line and exit 2 — reported before the host's return value is checked.
 *
 * Only the mutator thread runs this group (M3): the crossing stack, the handle
 * table and the class access registry are single-threaded state.
 */
#include "deal/native/host.h"
#include "deal/native/container.h"
#include "deal/native/descriptor.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

#include <string.h>

/* =========================================================================
 * Small platform-owned helpers
 * ========================================================================= */

/* Grows one platform-owned array, twice its size, preserving its entries. The
 * doubled capacity and both byte sizes are checked arithmetic (R10): a size
 * that cannot exist is the one deterministic resource fault before any storage
 * is allocated, never a wrapped short region and never a copy past the old
 * block. */
static void *deal_host_grow(void *storage, size_t *capacity, size_t needed,
                            size_t element_size, const char *reason) {
    if (needed < *capacity) {
        return storage;
    }
    size_t grown = *capacity == 0u ? 4u : *capacity * 2u;
    if (grown <= *capacity) {
        deal_platform_fault("size arithmetic: multiplication overflow");
    }
    while (grown <= needed) {
        if (grown > SIZE_MAX / 2u) {
            deal_platform_fault("size arithmetic: multiplication overflow");
        }
        grown *= 2u;
    }
    void *next = deal_platform_allocate(
        deal_platform_checked_multiply(grown, element_size));
    if (next == NULL) {
        deal_platform_fault(reason);
    }
    if (storage != NULL && needed != 0u) {
        memcpy(next, storage,
            deal_platform_checked_multiply(needed, element_size));
    }
    deal_platform_release(storage);
    *capacity = grown;
    return next;
}

static int deal_host_text_equal(const char *left, const char *right) {
    if (left == NULL || right == NULL) {
        return left == right;
    }
    return strcmp(left, right) == 0;
}

/* The one deterministic host protocol violation (H2/H5): one stderr line and
 * exit 2, never a DEAL-visible Error. */
static void deal_host_protocol(const char *reason) {
    static const char prefix[] = "DEAL_HOST_PROTOCOL: ";
    deal_platform_write_stderr(prefix, sizeof prefix - 1);
    deal_platform_write_stderr(reason, strlen(reason));
    deal_platform_write_stderr("\n", 1);
    deal_platform_flush_stderr();
    deal_platform_exit(2);
}

/* The decimal text of one non-negative count, into a caller buffer. */
static const char *deal_host_count_text(size_t value, char *buffer,
                                        size_t capacity) {
    char digits[24];
    size_t count = 0u;
    do {
        digits[count++] = (char)('0' + value % 10u);
        value /= 10u;
    } while (value != 0u && count < sizeof digits);
    size_t written = 0u;
    while (count != 0u && written + 1u < capacity) {
        buffer[written++] = digits[--count];
    }
    buffer[written] = '\0';
    return buffer;
}

/* =========================================================================
 * The closed descriptor grammar, checked without side effects
 *
 * The `descriptor` group's parser reports malformed text as a producer defect,
 * which is right for emitted static data. Host-supplied text reaches the value
 * surface at run time instead, so the host group checks the text against the
 * same published grammar before it interns it: a text outside the grammar is the
 * closed `DEAL_STATUS_BAD_DESCRIPTOR` status, never a process fault and never a
 * silently accepted descriptor. Interning itself stays the one parser.
 * ========================================================================= */

typedef struct {
    const char *text;
    size_t length;
} deal_host_grammar;

static int deal_host_grammar_peek(const deal_host_grammar *grammar,
                                  size_t position) {
    return position < grammar->length ? (unsigned char)grammar->text[position]
                                      : -1;
}

static int deal_host_grammar_literal(const deal_host_grammar *grammar,
                                     size_t position, const char *literal) {
    size_t length = strlen(literal);
    if (position + length > grammar->length) {
        return 0;
    }
    return strncmp(grammar->text + position, literal, length) == 0;
}

static int deal_host_grammar_data(const deal_host_grammar *grammar,
                                  size_t *position);

static int deal_host_grammar_class(const deal_host_grammar *grammar,
                                   size_t *position) {
    if (deal_host_grammar_peek(grammar, *position) != '@') {
        return 0;
    }
    size_t segment_start = *position + 1u;
    size_t cursor = segment_start;
    size_t last_slash = grammar->length;
    while (cursor < grammar->length) {
        int character = deal_host_grammar_peek(grammar, cursor);
        /* The published parser's class atom is the maximal run up to the
         * enclosing descriptor's terminator, so `$external/...` and the other
         * module-id spellings are representable; a control byte is outside it. */
        if (character == ')' || character == ']' || character == ',') {
            break;
        }
        if (character < 0x20 || character == 0x7F) {
            return 0;
        }
        if (character == '/') {
            last_slash = cursor;
        }
        cursor += 1u;
    }
    /* A canonical atom names a non-empty module path and a non-empty class
     * name, and the reserved `$builtin` namespace belongs to the bare `Error`
     * atom alone (the published parser's rule). */
    if (last_slash == grammar->length || last_slash == segment_start
            || last_slash + 1u == cursor) {
        return 0;
    }
    size_t module_length = last_slash - segment_start;
    if ((module_length == 8u
                && strncmp(grammar->text + segment_start, "$builtin", 8u) == 0)
            || (module_length > 8u
                && strncmp(grammar->text + segment_start, "$builtin/", 9u) == 0)) {
        return 0;
    }
    *position = cursor;
    return 1;
}

static int deal_host_grammar_function(const deal_host_grammar *grammar,
                                      size_t *position) {
    size_t cursor = *position;
    if (deal_host_grammar_literal(grammar, cursor, "async")) {
        cursor += 5u;
    }
    if (deal_host_grammar_peek(grammar, cursor) != '(') {
        return 0;
    }
    cursor += 1u;
    if (deal_host_grammar_peek(grammar, cursor) == ')') {
        cursor += 1u;
    } else {
        for (;;) {
            if (!deal_host_grammar_data(grammar, &cursor)) {
                return 0;
            }
            int character = deal_host_grammar_peek(grammar, cursor);
            if (character == ',') {
                cursor += 1u;
                continue;
            }
            if (character == ')') {
                cursor += 1u;
                break;
            }
            return 0;
        }
    }
    if (!deal_host_grammar_literal(grammar, cursor, "->")) {
        return 0;
    }
    cursor += 2u;
    if (deal_host_grammar_literal(grammar, cursor, "void")) {
        *position = cursor + 4u;
        return 1;
    }
    if (!deal_host_grammar_data(grammar, &cursor)) {
        return 0;
    }
    *position = cursor;
    return 1;
}

/* One data descriptor: primitive, Error, class, array, nullish or function. */
static int deal_host_grammar_data(const deal_host_grammar *grammar,
                                  size_t *position) {
    static const char *const primitives[] = {
        "null|undefined", "null", "undefined", "boolean", "number", "string",
        "record", "unknown", "Error"
    };
    size_t cursor = *position;
    for (size_t index = 0; index < sizeof primitives / sizeof primitives[0];
            index += 1u) {
        if (deal_host_grammar_literal(grammar, cursor, primitives[index])) {
            *position = cursor + strlen(primitives[index]);
            return 1;
        }
    }
    int character = deal_host_grammar_peek(grammar, cursor);
    if (character == '?' || character == '!') {
        /* The published prefixes are `?`, `!` and `?!` in that order (spec
         * `RuntimeTypeDescriptor`), so only a leading `?` may be followed by one
         * `!`: `!?T`, `??T`, `!!T` and `?!?!T` are outside the grammar. */
        int admits_null = character == '?';
        int admits_undefined = character == '!';
        size_t base = cursor + 1u;
        if (admits_null && deal_host_grammar_peek(grammar, base) == '!') {
            admits_undefined = 1;
            base += 1u;
        }
        /* A nullish prefix never nests, never prefixes an unknown or the
         * `null|undefined` union, and never re-states a member its base already
         * is, exactly as the published parser rejects those shapes. */
        int base_first = deal_host_grammar_peek(grammar, base);
        if (base_first == '?' || base_first == '!') {
            return 0;
        }
        if (deal_host_grammar_literal(grammar, base, "unknown")
                || deal_host_grammar_literal(grammar, base,
                    "null|undefined")) {
            return 0;
        }
        if (admits_null && deal_host_grammar_literal(grammar, base, "null")) {
            return 0;
        }
        if (admits_undefined
                && deal_host_grammar_literal(grammar, base, "undefined")) {
            return 0;
        }
        if (!deal_host_grammar_data(grammar, &base)) {
            return 0;
        }
        *position = base;
        return 1;
    }
    if (character == '[') {
        size_t element = cursor + 1u;
        if (!deal_host_grammar_data(grammar, &element)) {
            return 0;
        }
        if (deal_host_grammar_peek(grammar, element) != ']') {
            return 0;
        }
        *position = element + 1u;
        return 1;
    }
    if (character == '@') {
        return deal_host_grammar_class(grammar, position);
    }
    if (character == '('
            || deal_host_grammar_literal(grammar, cursor, "async")) {
        return deal_host_grammar_function(grammar, position);
    }
    return 0;
}

/* 1 when `text` is one complete canonical data descriptor. */
static int deal_host_descriptor_is_data(const char *text) {
    if (text == NULL || text[0] == '\0') {
        return 0;
    }
    deal_host_grammar grammar = {text, strlen(text)};
    size_t position = 0u;
    if (!deal_host_grammar_data(&grammar, &position)) {
        return 0;
    }
    return position == grammar.length;
}

/* 1 when `text` is one complete canonical function descriptor. */
static int deal_host_descriptor_is_function(const char *text) {
    if (text == NULL || text[0] == '\0') {
        return 0;
    }
    deal_host_grammar grammar = {text, strlen(text)};
    size_t position = 0u;
    if (!deal_host_grammar_function(&grammar, &position)) {
        return 0;
    }
    return position == grammar.length;
}

/* 1 when `text` is one canonical nominal class atom (`@module/Class` or the
 * builtin `Error` atom). */
static int deal_host_descriptor_is_class_atom(const char *text) {
    if (text == NULL || text[0] == '\0') {
        return 0;
    }
    if (strcmp(text, "Error") == 0) {
        return 1;
    }
    deal_host_grammar grammar = {text, strlen(text)};
    size_t position = 0u;
    if (!deal_host_grammar_class(&grammar, &position)) {
        return 0;
    }
    return position == grammar.length;
}

/*
 * 1 when `bytes` (of `length`) is a Unicode scalar sequence and 0 otherwise. The
 * `text` group owns the one validator; the host group asks it and maps a
 * rejection to the closed invalid-Unicode presentation with its encoding defect
 * (the two pinned host-string inner reasons).
 */
static int deal_host_bytes_are_scalars(const void *bytes, size_t length,
                                       uint32_t *defect) {
    if (deal_text_is_scalar_sequence(bytes, length)) {
        return 1;
    }
    if (defect != NULL) {
        *defect = DEAL_UNICODE_MALFORMED;
    }
    const unsigned char *data = bytes;
    for (size_t index = 0; index + 2u < length; index += 1u) {
        if (data[index] != 0xEDu) {
            continue;
        }
        if (data[index + 1u] >= 0xA0u && data[index + 1u] <= 0xBFu) {
            if (defect != NULL) {
                *defect = DEAL_UNICODE_SURROGATE;
            }
            break;
        }
    }
    return 0;
}

/* =========================================================================
 * The boxed carrier (R11)
 * ========================================================================= */

typedef enum {
    DEAL_HOST_BOX_MISSING = 0,
    DEAL_HOST_BOX_NOTHING = 1,
    DEAL_HOST_BOX_NULL = 2,
    DEAL_HOST_BOX_UNDEFINED = 3,
    DEAL_HOST_BOX_BOOLEAN = 4,
    DEAL_HOST_BOX_NUMBER = 5,
    DEAL_HOST_BOX_STRING = 6,
    DEAL_HOST_BOX_ARRAY = 7,
    DEAL_HOST_BOX_RECORD = 8,
    DEAL_HOST_BOX_CLASS = 9,
    DEAL_HOST_BOX_CLASS_ATOM = 10,
    DEAL_HOST_BOX_FUNCTION = 11,
    DEAL_HOST_BOX_ASYNC_OPERATION = 12,
    DEAL_HOST_BOX_INVALID_UNICODE = 13
} deal_host_box_kind;

/* One array slot or one class field of a boxed carrier: presence metadata plus
 * the slot reference, so absence, present-undefined, present-null and
 * present-value stay distinct (R2, R11). */
typedef struct {
    uint8_t present;
    uint8_t reserved[7];
    deal_value value;
} deal_host_slot;

/* One record member, in the host's own insertion order. */
typedef struct {
    void *key_bytes;
    size_t key_length;
    deal_value value;
} deal_host_member;

typedef struct deal_host_box {
    deal_host_box_kind kind;
    int is_async;
    int boolean;
    double number;
    void *bytes;                     /* string/class-atom/signature text      */
    size_t length;
    const char *signature;           /* a function carrier's signature text   */
    deal_descriptor *element_descriptor; /* typed array's element atom        */
    deal_host_slot *slots;
    size_t slot_count;
    size_t slot_capacity;
    deal_host_member *members;
    size_t member_count;
    size_t member_capacity;
    deal_host_function function;
    deal_async_token token;
    uint32_t unicode_defect;
} deal_host_box;

/* The union of every runtime-owned element storage slot (C3), so one staged
 * element is always large enough for the layout it is written for. */
typedef union {
    deal_data datum;
    void *reference;
    double number;
    uint8_t boolean;
    deal_nullish_number_element nullish_number;
    deal_nullish_boolean_element nullish_boolean;
} deal_host_element;

/* =========================================================================
 * The crossing
 * ========================================================================= */

typedef struct {
    deal_error_token token;
    deal_error *error;
    int consumed;
} deal_host_token_entry;

typedef struct deal_host_crossing {
    deal_value_crossing *values;
    deal_host_box **boxes;
    size_t box_count;
    size_t box_capacity;
    deal_host_token_entry *tokens;
    size_t token_count;
    size_t token_capacity;
    deal_origin origin;
} deal_host_crossing;

static deal_host_crossing **deal_host_crossing_stack;
static size_t deal_host_crossing_depth;
static size_t deal_host_crossing_capacity;

/* The runtime-issued DEAL-origin failure token counter: one process-wide
 * monotonic sequence, never reused (H2). */
static uint64_t deal_host_token_counter;

static deal_host_crossing *deal_host_crossing_current(void) {
    return deal_host_crossing_depth == 0u
        ? NULL : deal_host_crossing_stack[deal_host_crossing_depth - 1u];
}

static deal_host_crossing *deal_host_crossing_push(const deal_origin *origin) {
    deal_host_crossing_stack = deal_host_grow(deal_host_crossing_stack,
        &deal_host_crossing_capacity, deal_host_crossing_depth,
        sizeof(*deal_host_crossing_stack),
        "host: crossing stack allocation failed");
    deal_host_crossing *crossing = deal_platform_allocate(sizeof(*crossing));
    if (crossing == NULL) {
        deal_platform_fault("host: crossing allocation failed");
    }
    memset(crossing, 0, sizeof(*crossing));
    crossing->values = deal_value_crossing_begin();
    if (origin != NULL) {
        crossing->origin = *origin;
    } else {
        crossing->origin.source_file = "";
        crossing->origin.line = 1;
        crossing->origin.column = 1;
    }
    deal_host_crossing_stack[deal_host_crossing_depth] = crossing;
    deal_host_crossing_depth += 1u;
    return crossing;
}

/* Releases one boxed carrier's platform storage. Boxes hold slot references,
 * never managed pointers, so no managed state is touched here. */
static void deal_host_box_free(deal_host_box *box) {
    if (box == NULL) {
        return;
    }
    if (box->kind == DEAL_HOST_BOX_RECORD || box->kind == DEAL_HOST_BOX_CLASS) {
        for (size_t index = 0; index < box->member_count; index += 1u) {
            deal_platform_release(box->members[index].key_bytes);
        }
    }
    deal_platform_release(box->slots);
    deal_platform_release(box->members);
    deal_platform_release(box->bytes);
    deal_platform_release(box);
}

static void deal_host_crossing_pop(deal_host_crossing *crossing) {
    if (crossing == NULL || crossing != deal_host_crossing_current()) {
        deal_platform_fault("host: end of a non-active crossing");
    }
    for (size_t index = 0; index < crossing->box_count; index += 1u) {
        deal_host_box_free(crossing->boxes[index]);
    }
    deal_platform_release(crossing->boxes);
    deal_platform_release(crossing->tokens);
    deal_value_crossing_end(crossing->values);
    deal_platform_release(crossing);
    deal_host_crossing_depth -= 1u;
    deal_host_crossing_stack[deal_host_crossing_depth] = NULL;
}

/* The unconsumed-token rule (H2): a token issued in this crossing that the host
 * neither relayed nor discarded is a protocol violation, reported before the
 * host's return value is checked. */
static void deal_host_crossing_require_tokens_consumed(
        const deal_host_crossing *crossing) {
    for (size_t index = 0; index < crossing->token_count; index += 1u) {
        if (crossing->tokens[index].consumed == 0) {
            deal_host_protocol("a DEAL failure token was neither relayed"
                               " nor discarded");
        }
    }
}

/* =========================================================================
 * Handles (H3)
 * ========================================================================= */

typedef struct {
    deal_handle handle;
    deal_data value;
    int live;
} deal_host_handle_entry;

static deal_host_handle_entry *deal_host_handles;
static size_t deal_host_handle_count;
static size_t deal_host_handle_capacity;
static uint64_t deal_host_handle_counter;
static int deal_host_handles_registered;

/* The handle table is a root source: every live handle roots its value (M5). */
static void deal_host_handle_scan(deal_reference_visit visit,
                                  void *visit_context, void *context) {
    (void)context;
    for (size_t index = 0; index < deal_host_handle_count; index += 1u) {
        if (deal_host_handles[index].live == 0) {
            continue;
        }
        void *referent = deal_data_referent(&deal_host_handles[index].value);
        if (referent != NULL) {
            visit(referent, visit_context);
        }
    }
}

static void deal_host_handles_ensure(void) {
    if (deal_host_handles_registered != 0) {
        return;
    }
    deal_host_handles_registered = 1;
    deal_root_source_register(deal_host_handle_scan, NULL);
}

static deal_host_handle_entry *deal_host_handle_find(deal_handle handle) {
    if (handle == 0u) {
        return NULL;
    }
    for (size_t index = 0; index < deal_host_handle_count; index += 1u) {
        if (deal_host_handles[index].handle == handle) {
            return deal_host_handles[index].live == 0
                ? NULL : &deal_host_handles[index];
        }
    }
    return NULL;
}

/* =========================================================================
 * The emitted class access registry
 * ========================================================================= */

typedef struct {
    const char *atom;
    const deal_host_class_access *access;
} deal_host_class_entry;

static deal_host_class_entry *deal_host_classes_emitted;
static size_t deal_host_class_count;
static size_t deal_host_class_capacity;

void deal_host_class_access_register(const char *class_atom,
                                     const deal_host_class_access *access) {
    if (class_atom == NULL || class_atom[0] == '\0' || access == NULL
            || access->declared == NULL || access->create == NULL
            || access->store == NULL || access->read == NULL) {
        deal_platform_fault("host: malformed emitted class access record");
    }
    for (size_t index = 0; index < deal_host_class_count; index += 1u) {
        if (!deal_host_text_equal(deal_host_classes_emitted[index].atom,
                class_atom)) {
            continue;
        }
        if (memcmp(deal_host_classes_emitted[index].access, access,
                sizeof(*access)) != 0) {
            deal_platform_fault("host: one class atom registered twice");
        }
        return;
    }
    deal_host_classes_emitted = deal_host_grow(deal_host_classes_emitted,
        &deal_host_class_capacity, deal_host_class_count,
        sizeof(*deal_host_classes_emitted),
        "host: class access table allocation failed");
    deal_host_classes_emitted[deal_host_class_count].atom = class_atom;
    deal_host_classes_emitted[deal_host_class_count].access = access;
    deal_host_class_count += 1u;
}

const deal_host_class_access *deal_host_class_access_of(const char *class_atom) {
    if (class_atom == NULL) {
        return NULL;
    }
    for (size_t index = 0; index < deal_host_class_count; index += 1u) {
        if (deal_host_text_equal(deal_host_classes_emitted[index].atom,
                class_atom)) {
            return deal_host_classes_emitted[index].access;
        }
    }
    return NULL;
}

/* The index of one declared class field, or -1. */
static long deal_host_class_field_index(const deal_class_layout *layout,
                                        const char *name) {
    if (layout == NULL || name == NULL) {
        return -1;
    }
    for (size_t index = 0; index < layout->field_count; index += 1u) {
        if (deal_host_text_equal(layout->fields[index].name, name)) {
            return (long)index;
        }
    }
    return -1;
}

/* One class carrier's stored member for a declared field name, or NULL when the
 * host supplied no member (R11's absence). */
static const deal_host_member *deal_host_class_member(const deal_host_box *box,
                                                      const char *field) {
    if (box == NULL || field == NULL) {
        return NULL;
    }
    for (size_t index = 0; index < box->member_count; index += 1u) {
        if (deal_host_text_equal(box->members[index].key_bytes, field)) {
            return &box->members[index];
        }
    }
    return NULL;
}

/* The declared access record of one managed class instance, from its nominal
 * tag: every registered class answers through its own read seam (H2/C3 opacity).
 * The builtin `Error` instance answers NULL with the `Error` atom. */
static const deal_host_class_access *deal_host_class_access_of_instance(
        void *instance, const char **atom_out) {
    const deal_data datum = {DEAL_DATA_CLASS, 0u, 0.0, instance};
    if (deal_object_layout(instance) == DEAL_LAYOUT_ERROR) {
        if (atom_out != NULL) {
            *atom_out = "Error";
        }
        return NULL;
    }
    for (size_t index = 0; index < deal_host_class_count; index += 1u) {
        const deal_host_class_access *access =
            deal_host_classes_emitted[index].access;
        deal_check_input probe = {0};
        uint8_t present = 0;
        if (access->read(&datum, 0u, &probe, &present, access->context) != 0) {
            if (atom_out != NULL) {
                *atom_out = deal_host_classes_emitted[index].atom;
            }
            return access;
        }
    }
    if (atom_out != NULL) {
        *atom_out = NULL;
    }
    return NULL;
}

/* =========================================================================
 * Carriers
 * ========================================================================= */

static deal_status deal_host_datum_carrier(deal_data datum, deal_value *out);
static deal_status deal_host_publish_box(deal_host_box_kind kind,
                                         deal_value *out);

/* Issues one carrier for one present slot reference: a nested carrier keeps its
 * own identity, so two slots naming one host instance stay one value (R11). */
static deal_status deal_host_slot_carrier(deal_value value, deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    *out = value;
    return DEAL_OK;
}

static deal_status deal_host_issue_view(void *referent, deal_value *out) {
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    return deal_value_crossing_issue(crossing->values, referent, out);
}

static deal_host_box *deal_host_box_new(deal_host_crossing *crossing,
                                        deal_host_box_kind kind) {
    deal_host_box *box = deal_platform_allocate(sizeof(*box));
    if (box == NULL) {
        deal_platform_fault("host: boxed carrier allocation failed");
    }
    memset(box, 0, sizeof(*box));
    box->kind = kind;
    crossing->boxes = deal_host_grow(crossing->boxes,
        &crossing->box_capacity, crossing->box_count,
        sizeof(*crossing->boxes),
        "host: boxed carrier table allocation failed");
    crossing->boxes[crossing->box_count] = box;
    crossing->box_count += 1u;
    return box;
}

static deal_status deal_host_box_publish(deal_host_box *box, deal_value *out) {
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    return deal_value_crossing_issue_opaque(crossing->values, box, out);
}

/* The box one carrier names, or NULL when the carrier is a managed view. */
static deal_host_box *deal_host_box_of(deal_host_crossing *crossing,
                                       void *payload) {
    if (crossing == NULL || payload == NULL) {
        return NULL;
    }
    for (size_t index = 0; index < crossing->box_count; index += 1u) {
        if (crossing->boxes[index] == payload) {
            return crossing->boxes[index];
        }
    }
    return NULL;
}

/* Resolves one carrier in the current crossing: exactly one of `*box` and
 * `*referent` is set on success. */
static deal_status deal_host_resolve(deal_value value, deal_host_box **box,
                                    void **referent) {
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (box != NULL) {
        *box = NULL;
    }
    if (referent != NULL) {
        *referent = NULL;
    }
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    void *payload = NULL;
    deal_status status = deal_value_crossing_resolve(crossing->values, value,
        &payload);
    if (status != DEAL_OK) {
        return status;
    }
    deal_host_box *found = deal_host_box_of(crossing, payload);
    if (found != NULL) {
        if (box != NULL) {
            *box = found;
        }
        return DEAL_OK;
    }
    if (referent != NULL) {
        *referent = payload;
    }
    return DEAL_OK;
}

/* The managed object's actual kind, from its runtime layout identity. */
static deal_value_kind deal_host_kind_of_object(const void *referent) {
    if (deal_value_is_null(referent)) {
        return DEAL_KIND_NULL;
    }
    if (deal_value_is_undefined(referent)) {
        return DEAL_KIND_UNDEFINED;
    }
    switch (deal_object_layout(referent)) {
    case DEAL_LAYOUT_STRING:
        return DEAL_KIND_STRING;
    case DEAL_LAYOUT_ARRAY:
        return DEAL_KIND_ARRAY;
    case DEAL_LAYOUT_TABLE:
        return DEAL_KIND_TABLE;
    case DEAL_LAYOUT_ERROR:
        return DEAL_KIND_CLASS;
    case DEAL_LAYOUT_FUNCTION:
        return DEAL_KIND_FUNCTION;
    default:
        break;
    }
    if (deal_object_layout(referent) >= DEAL_LAYOUT_EMITTED_BASE) {
        return DEAL_KIND_CLASS;
    }
    deal_platform_fault("host: a carrier names no closed DEAL value kind");
    return DEAL_KIND_CLASS;
}

static deal_value_kind deal_host_kind_of_box(const deal_host_box *box) {
    switch (box->kind) {
    case DEAL_HOST_BOX_MISSING:
        return DEAL_KIND_MISSING;
    case DEAL_HOST_BOX_NOTHING:
        return DEAL_KIND_NOTHING;
    case DEAL_HOST_BOX_NULL:
        return DEAL_KIND_NULL;
    case DEAL_HOST_BOX_UNDEFINED:
        return DEAL_KIND_UNDEFINED;
    case DEAL_HOST_BOX_BOOLEAN:
        return DEAL_KIND_BOOLEAN;
    case DEAL_HOST_BOX_NUMBER:
        return DEAL_KIND_NUMBER;
    case DEAL_HOST_BOX_STRING:
        return DEAL_KIND_STRING;
    case DEAL_HOST_BOX_ARRAY:
        return DEAL_KIND_ARRAY;
    case DEAL_HOST_BOX_RECORD:
        return DEAL_KIND_TABLE;
    case DEAL_HOST_BOX_CLASS:
    case DEAL_HOST_BOX_CLASS_ATOM:
        return DEAL_KIND_CLASS;
    case DEAL_HOST_BOX_FUNCTION:
        return DEAL_KIND_FUNCTION;
    case DEAL_HOST_BOX_ASYNC_OPERATION:
        return DEAL_KIND_ASYNC_OPERATION;
    case DEAL_HOST_BOX_INVALID_UNICODE:
        return DEAL_KIND_INVALID_UNICODE;
    }
    deal_platform_fault("host: a boxed carrier names no closed value kind");
    return DEAL_KIND_NULL;
}

/* The persistent datum of one managed referent. */
static deal_data deal_host_datum_of_referent(void *referent) {
    switch (deal_host_kind_of_object(referent)) {
    case DEAL_KIND_NULL:
        return deal_data_of_null();
    case DEAL_KIND_UNDEFINED:
        return deal_data_of_undefined();
    case DEAL_KIND_STRING:
        return deal_data_of_reference(DEAL_DATA_STRING, referent);
    case DEAL_KIND_ARRAY:
        return deal_data_of_reference(DEAL_DATA_ARRAY, referent);
    case DEAL_KIND_TABLE:
        return deal_data_of_reference(DEAL_DATA_TABLE, referent);
    case DEAL_KIND_CLASS:
        return deal_data_of_reference(
            deal_object_layout(referent) == DEAL_LAYOUT_ERROR
                ? DEAL_DATA_ERROR : DEAL_DATA_CLASS, referent);
    case DEAL_KIND_FUNCTION:
        return deal_data_of_reference(DEAL_DATA_FUNCTION, referent);
    default:
        break;
    }
    deal_platform_fault("host: a referent names no persistent data kind");
    return deal_data_of_undefined();
}

/* Roots one materialized object for the crossing's lifetime (M5). */
static void deal_host_keep(deal_host_crossing *crossing, void *referent) {
    if (referent == NULL || deal_value_is_null(referent)
            || deal_value_is_undefined(referent)) {
        return;
    }
    if (deal_value_crossing_root(crossing->values, referent) != DEAL_OK) {
        deal_platform_fault("host: materialized object rooting failed");
    }
}

/* The host group's own function-wrapper layout (H4): one host entry pointer
 * and nothing the collector traces, so its precise map is empty. */
static void deal_host_layouts_ensure(void) {
    static int defined;
    if (defined != 0) {
        return;
    }
    defined = 1;
    static const deal_layout hostFunctionLayout = {
        DEAL_LAYOUT_HOST_FUNCTION, 0u, sizeof(deal_host_function), NULL, 0u
    };
    deal_layout_define(&hostFunctionLayout);
}

/* =========================================================================
 * Materialization (R11)
 * ========================================================================= */

typedef struct {
    deal_host_box **boxes;
    deal_data *values;
    size_t count;
    size_t box_capacity;
    size_t value_capacity;
} deal_host_memo;

static deal_data *deal_host_memo_lookup(deal_host_memo *memo,
                                        deal_host_box *box) {
    for (size_t index = 0; index < memo->count; index += 1u) {
        if (memo->boxes[index] == box) {
            return &memo->values[index];
        }
    }
    return NULL;
}

static void deal_host_memo_record(deal_host_memo *memo, deal_host_box *box,
                                  deal_data value) {
    memo->boxes = deal_host_grow(memo->boxes, &memo->box_capacity, memo->count,
        sizeof(*memo->boxes), "host: materialization memo allocation failed");
    memo->values = deal_host_grow(memo->values, &memo->value_capacity,
        memo->count, sizeof(*memo->values),
        "host: materialization memo allocation failed");
    memo->boxes[memo->count] = box;
    memo->values[memo->count] = value;
    memo->count += 1u;
}

static void deal_host_memo_release(deal_host_memo *memo) {
    deal_platform_release(memo->boxes);
    deal_platform_release(memo->values);
}

/* The one materialization entry: converts one boxed carrier into the managed
 * representation, memoized per boxed node so alias identity inside the returned
 * graph is preserved and a self-referential graph terminates. A failing element
 * or class-field admission answers that arm's carried Error. */
static deal_error *deal_host_materialize(deal_host_crossing *crossing,
                                         deal_host_box *box,
                                         deal_host_memo *memo,
                                         deal_data *out);

/* Renders the array-element admission arm of one failing element: the declared
 * element descriptor as the arm's `expected` field, the checked presentation's
 * own typed-boundary token as its `actual` field (descriptor.h publishes
 * `deal_check_token` for exactly this), the one-based index parameter and the
 * leaf descriptor failure as the cause (R5's array-element row). */
static deal_error *deal_host_element_arm(deal_host_crossing *crossing,
                                         size_t one_based_index,
                                         deal_descriptor *element,
                                         const deal_check_input *presented,
                                         deal_error *cause) {
    char index_text[24];
    deal_arm_parameter parameter = {
        "oneBasedIndex", deal_host_count_text(one_based_index, index_text,
            sizeof index_text)};
    deal_check_input absent;
    if (presented == NULL) {
        /* An absent slot is the absent marker of the closed presentation set. */
        memset(&absent, 0, sizeof absent);
        absent.presentation = DEAL_PRESENT_MISSING;
        presented = &absent;
    }
    return deal_error_materialize(DEAL_ARM_ID_ARRAY_ELEMENT_KIND, &parameter,
        1u, deal_descriptor_text(element), deal_check_token(presented),
        &crossing->origin, cause);
}

/*
 * The pinned rejection of one malformed carrier presentation: the host cell arm
 * whose inner reason is the declared position's own typed-boundary arm (R5). It
 * is the same tuple the one descriptor-driven check renders, selected for a
 * carrier the check cannot classify as data at all — the untyped-array negative
 * presentation of H2.
 */
static deal_error *deal_host_carrier_reject(deal_descriptor *declared,
                                            const char *actual,
                                            const deal_origin *origin) {
    char *inner = deal_arm_message(DEAL_ARM_ID_TYPED_BOUNDARY_KIND,
        &(deal_arm_parameter){"kind", deal_descriptor_kind_text(declared)}, 1u);
    deal_arm_parameter parameter = {"inner", inner};
    deal_error *error = deal_error_materialize(DEAL_ARM_ID_HOST_SYNC_RETURN_CELL,
        &parameter, 1u, deal_descriptor_text(declared), actual, origin, NULL);
    deal_arm_text_release(inner);
    return error;
}

/* 1 when one carrier is the untyped-array negative presentation (H2: never
 * admitted as DEAL data, including under `unknown`). */
static int deal_host_is_untyped_array(deal_host_crossing *crossing,
                                      deal_value value) {
    deal_host_box *box = NULL;
    (void)crossing;
    if (deal_host_resolve(value, &box, NULL) != DEAL_OK) {
        return 0;
    }
    return box != NULL && box->kind == DEAL_HOST_BOX_ARRAY
        && box->element_descriptor == NULL;
}

/* Stages one materialized element into its declared storage slot (C3). */
static void deal_host_stage_element(uint32_t layout, const deal_data *value,
                                    void *slot) {
    switch (layout) {
    case DEAL_LAYOUT_ELEMENT_DATUM:
        memcpy(slot, value, sizeof(*value));
        return;
    case DEAL_LAYOUT_ELEMENT_REFERENCE: {
        void *referent = NULL;
        switch ((deal_data_tag)value->tag) {
        case DEAL_DATA_NULL:
            referent = deal_null_singleton;
            break;
        case DEAL_DATA_UNDEFINED:
            referent = deal_undefined_singleton;
            break;
        case DEAL_DATA_STRING:
        case DEAL_DATA_ARRAY:
        case DEAL_DATA_TABLE:
        case DEAL_DATA_CLASS:
        case DEAL_DATA_ERROR:
        case DEAL_DATA_FUNCTION:
            referent = value->reference;
            break;
        default:
            deal_platform_fault("host: a datum outside the reference set at a"
                                " reference element slot");
        }
        memcpy(slot, &referent, sizeof(referent));
        return;
    }
    case DEAL_LAYOUT_ELEMENT_NUMBER:
        if (value->tag != DEAL_DATA_NUMBER) {
            deal_platform_fault("host: a non-number datum at a number element"
                                " slot");
        }
        memcpy(slot, &value->number, sizeof(value->number));
        return;
    case DEAL_LAYOUT_ELEMENT_BOOLEAN: {
        if (value->tag != DEAL_DATA_BOOLEAN) {
            deal_platform_fault("host: a non-boolean datum at a boolean element"
                                " slot");
        }
        uint8_t truth = value->number != 0.0 ? 1u : 0u;
        memcpy(slot, &truth, sizeof(truth));
        return;
    }
    case DEAL_LAYOUT_ELEMENT_NULLISH_NUMBER: {
        deal_nullish_number_element staged;
        staged.tag = DEAL_NULLISH_BASE;
        staged.value = 0.0;
        if (value->tag == DEAL_DATA_NULL) {
            staged.tag = DEAL_NULLISH_NULL;
        } else if (value->tag == DEAL_DATA_UNDEFINED) {
            staged.tag = DEAL_NULLISH_UNDEFINED;
        } else if (value->tag == DEAL_DATA_NUMBER) {
            staged.value = value->number;
        } else {
            deal_platform_fault("host: a datum outside the scalar-nullish set"
                                " at a nullish number element slot");
        }
        memcpy(slot, &staged, sizeof(staged));
        return;
    }
    case DEAL_LAYOUT_ELEMENT_NULLISH_BOOLEAN: {
        deal_nullish_boolean_element staged;
        staged.tag = DEAL_NULLISH_BASE;
        staged.value = 0u;
        if (value->tag == DEAL_DATA_NULL) {
            staged.tag = DEAL_NULLISH_NULL;
        } else if (value->tag == DEAL_DATA_UNDEFINED) {
            staged.tag = DEAL_NULLISH_UNDEFINED;
        } else if (value->tag == DEAL_DATA_BOOLEAN) {
            staged.value = value->number != 0.0 ? 1u : 0u;
        } else {
            deal_platform_fault("host: a datum outside the scalar-nullish set"
                                " at a nullish boolean element slot");
        }
        memcpy(slot, &staged, sizeof(staged));
        return;
    }
    default:
        deal_platform_fault("host: element storage layout outside the published"
                            " set");
    }
}

/* The runtime-owned element storage layout of one interned element descriptor
 * (C3, R2): the mapping the container and JSON groups read too. */
static uint32_t deal_host_element_storage(deal_descriptor *element) {
    if (element == NULL) {
        deal_platform_fault("host: array position without its element"
                            " descriptor");
    }
    if (deal_descriptor_type(element) == DEAL_DESCRIPTOR_NULLISH) {
        deal_descriptor *base = deal_descriptor_inner(element);
        switch (deal_descriptor_type(base)) {
        case DEAL_DESCRIPTOR_NUMBER:
            return DEAL_LAYOUT_ELEMENT_NULLISH_NUMBER;
        case DEAL_DESCRIPTOR_BOOLEAN:
            return DEAL_LAYOUT_ELEMENT_NULLISH_BOOLEAN;
        default:
            return DEAL_LAYOUT_ELEMENT_REFERENCE;
        }
    }
    switch (deal_descriptor_type(element)) {
    case DEAL_DESCRIPTOR_UNKNOWN:
        return DEAL_LAYOUT_ELEMENT_DATUM;
    case DEAL_DESCRIPTOR_NUMBER:
        return DEAL_LAYOUT_ELEMENT_NUMBER;
    case DEAL_DESCRIPTOR_BOOLEAN:
        return DEAL_LAYOUT_ELEMENT_BOOLEAN;
    default:
        return DEAL_LAYOUT_ELEMENT_REFERENCE;
    }
}

/* The closed boundary kind of one declared descriptor (R4/D3): a function
 * descriptor belongs to the `FUNCTION_SIGNATURE` boundary and every other
 * descriptor to `TYPE_DESCRIPTOR`, which is the pairing the one descriptor-driven
 * check enforces (the emitted module's own `checkBoundaryKind`). A declared
 * function position of the value surface — a handle's requested descriptor, a
 * declared class field, a typed array's persistent element descriptor and an
 * array element's own position — therefore compares the value's signature atom
 * at its own boundary instead of reaching the typed boundary's invariant fault.
 * A nullish position is its own descriptor kind, so its check reaches a function
 * base through `TYPE_DESCRIPTOR`. */
static uint32_t deal_host_boundary_of(deal_descriptor *declared) {
    return deal_descriptor_type(declared) == DEAL_DESCRIPTOR_FUNCTION
        ? DEAL_BOUNDARY_FUNCTION_SIGNATURE : DEAL_BOUNDARY_TYPE_DESCRIPTOR;
}

/* The check presentation of one materialized datum: the carried class atom and
 * the function signature come from the value itself (R2/R3/R4), exactly as the
 * crossing's own classification of a view datum derives them, and never from
 * the requested descriptor. A check at a class or function position therefore
 * compares the value's own nominal identity and signature, so a class of
 * another identity, a function of another signature and a class datum at a
 * position outside the class grammar all select the closed kind/identity arm
 * instead of matching by construction. */
static void deal_host_present(deal_data datum, deal_check_input *out) {
    memset(out, 0, sizeof(*out));
    out->presentation = DEAL_PRESENT_DATUM;
    out->datum = datum;
    void *referent = deal_data_referent(&datum);
    if (referent == NULL) {
        return;
    }
    switch (deal_host_kind_of_object(referent)) {
    case DEAL_KIND_FUNCTION:
        out->signature = deal_fn_signature(referent);
        return;
    case DEAL_KIND_CLASS: {
        const char *atom = NULL;
        (void)deal_host_class_access_of_instance(referent, &atom);
        if (atom == NULL) {
            deal_platform_fault("host: a class carrier of no emitted layout");
        }
        out->class_atom = atom;
        return;
    }
    default:
        return;
    }
}

static deal_error *deal_host_materialize_array(deal_host_crossing *crossing,
                                               deal_host_box *box,
                                               deal_host_memo *memo,
                                               deal_data *out) {
    deal_descriptor *element = box->element_descriptor;
    if (element == NULL) {
        /* The untyped negative presentation is never admitted as DEAL data
         * (H2/R11): the position's own descriptor check rejects it. */
        return NULL;
    }
    uint32_t layout = deal_host_element_storage(element);
    deal_array *array = deal_container_array_new_typed(layout,
        deal_descriptor_reference(element), 0u);
    deal_host_keep(crossing, array);
    deal_data created = deal_data_of_reference(DEAL_DATA_ARRAY, array);
    deal_host_memo_record(memo, box, created);
    for (size_t index = 0; index < box->slot_count; index += 1u) {
        deal_host_slot *slot = &box->slots[index];
        if (slot->present == 0) {
            /* A hole is not admitted data: the element position carries no
             * value at all (H2/R11). */
            return deal_host_element_arm(crossing, index + 1u, element, NULL,
                NULL);
        }
        deal_host_box *inner = NULL;
        void *referent = NULL;
        if (deal_host_resolve(slot->value, &inner, &referent) != DEAL_OK) {
            deal_platform_fault("host: array slot names a foreign carrier");
        }
        if (inner != NULL && inner->kind == DEAL_HOST_BOX_ARRAY
                && inner->element_descriptor == NULL) {
            return deal_host_element_arm(crossing, index + 1u, element, NULL,
                deal_host_carrier_reject(element, "array", &crossing->origin));
        }
        if (inner != NULL) {
            switch (inner->kind) {
            case DEAL_HOST_BOX_MISSING:
            case DEAL_HOST_BOX_NOTHING:
            case DEAL_HOST_BOX_ASYNC_OPERATION:
            case DEAL_HOST_BOX_INVALID_UNICODE: {
                /* A diagnostic carrier presentation is never an element
                 * (R2/R11): the element position rejects it with its own arm,
                 * `missing` included, whose shared check normalization to
                 * `undefined` is the absent-slot read projection. */
                deal_check_input presented;
                memset(&presented, 0, sizeof presented);
                presented.presentation =
                    inner->kind == DEAL_HOST_BOX_MISSING
                        ? DEAL_PRESENT_MISSING
                        : inner->kind == DEAL_HOST_BOX_NOTHING
                            ? DEAL_PRESENT_NOTHING
                            : inner->kind == DEAL_HOST_BOX_ASYNC_OPERATION
                                ? DEAL_PRESENT_ASYNC_OPERATION
                                : DEAL_PRESENT_INVALID_UNICODE;
                presented.unicode_defect = inner->unicode_defect;
                deal_check_context context;
                context.origin = &crossing->origin;
                context.parameter_index = 0;
                deal_outcome rejected = deal_check_value(element, presented,
                    deal_host_boundary_of(element), &context);
                return deal_host_element_arm(crossing, index + 1u, element,
                    &presented, rejected.error);
            }
            default:
                break;
            }
        }
        deal_data element_value = deal_data_of_undefined();
        if (inner != NULL) {
            deal_error *cause = deal_host_materialize(crossing, inner, memo,
                &element_value);
            if (cause != NULL) {
                return cause;
            }
        } else {
            element_value = deal_host_datum_of_referent(referent);
        }
        deal_check_input checked;
        deal_host_present(element_value, &checked);
        deal_check_context context;
        context.origin = &crossing->origin;
        context.parameter_index = 0;
        deal_outcome admitted = deal_check_value(element, checked,
            deal_host_boundary_of(element), &context);
        if (admitted.error != NULL) {
            return deal_host_element_arm(crossing, index + 1u, element,
                &checked, admitted.error);
        }
        deal_host_element staged;
        memset(&staged, 0, sizeof staged);
        deal_host_stage_element(layout, &element_value, &staged);
        deal_container_array_append(array, &staged);
    }
    *out = created;
    return NULL;
}

/* The check presentation of one carrier and, when the carrier is host-built,
 * the materialization failure that selected it (R11): the crossing reports
 * that failure as its own carried Error instead of an infrastructure fault. */
static deal_status deal_host_classify_into(deal_host_crossing *crossing,
                                           deal_value value,
                                           deal_host_memo *memo,
                                           deal_check_input *out,
                                           deal_error **cause);

/* Renders the record-position rejection of one non-data carrier presentation
 * (R11: unknown ingress accepts supported validated data only). */
static deal_error *deal_host_record_reject(deal_host_crossing *crossing,
                                           const deal_check_input *presented) {
    if (presented->presentation == DEAL_PRESENT_MISSING) {
        /* The absence marker is presence metadata, never a member (R2/R11): the
         * shared check's MISSING -> undefined normalization is the absent-read
         * projection and would otherwise admit it as an `unknown` member. */
        return deal_host_carrier_reject(deal_descriptor_intern("unknown"),
            "nil", &crossing->origin);
    }
    deal_check_context context;
    context.origin = &crossing->origin;
    context.parameter_index = 0;
    deal_check_input value = *presented;
    deal_outcome rejected = deal_check_value(deal_descriptor_intern("unknown"),
        value, DEAL_BOUNDARY_TYPE_DESCRIPTOR, &context);
    return rejected.error;
}

static deal_error *deal_host_materialize_record(deal_host_crossing *crossing,
                                                deal_host_box *box,
                                                deal_host_memo *memo,
                                                deal_data *out) {
    deal_table *table = deal_container_table_new();
    deal_host_keep(crossing, table);
    deal_data created = deal_data_of_reference(DEAL_DATA_TABLE, table);
    deal_host_memo_record(memo, box, created);
    for (size_t index = 0; index < box->member_count; index += 1u) {
        deal_host_member *member = &box->members[index];
        uint32_t defect = 0u;
        if (!deal_host_bytes_are_scalars(member->key_bytes, member->key_length,
                &defect)) {
            /* A malformed record key is never admitted (R2/R11): the inner
             * reason is the pinned host-string arm, never invented text. */
            char *inner = deal_arm_message(defect == DEAL_UNICODE_SURROGATE
                ? DEAL_ARM_ID_HOST_STRING_SURROGATE
                : DEAL_ARM_ID_HOST_STRING_INVALID_UTF8, NULL, 0u);
            deal_arm_parameter parameter = {"inner", inner};
            deal_error *error = deal_error_materialize(
                DEAL_ARM_ID_HOST_SYNC_RETURN_CELL, &parameter, 1u,
                "", "invalid-unicode", &crossing->origin, NULL);
            deal_arm_text_release(inner);
            return error;
        }
        deal_string *key = deal_text_new(member->key_bytes, member->key_length);
        deal_host_keep(crossing, key);
        deal_host_box *inner = NULL;
        void *referent = NULL;
        if (deal_host_resolve(member->value, &inner, &referent) != DEAL_OK) {
            deal_platform_fault("host: record member names a foreign carrier");
        }
        deal_data value = deal_data_of_undefined();
        if (inner != NULL) {
            switch (inner->kind) {
            case DEAL_HOST_BOX_MISSING:
            case DEAL_HOST_BOX_NOTHING:
            case DEAL_HOST_BOX_ASYNC_OPERATION:
            case DEAL_HOST_BOX_INVALID_UNICODE: {
                deal_check_input presented;
                memset(&presented, 0, sizeof presented);
                presented.presentation =
                    inner->kind == DEAL_HOST_BOX_MISSING
                        ? DEAL_PRESENT_MISSING
                        : inner->kind == DEAL_HOST_BOX_NOTHING
                            ? DEAL_PRESENT_NOTHING
                            : inner->kind == DEAL_HOST_BOX_ASYNC_OPERATION
                                ? DEAL_PRESENT_ASYNC_OPERATION
                                : DEAL_PRESENT_INVALID_UNICODE;
                presented.unicode_defect = inner->unicode_defect;
                return deal_host_record_reject(crossing, &presented);
            }
            default:
                break;
            }
            if (inner->kind == DEAL_HOST_BOX_ARRAY
                    && inner->element_descriptor == NULL) {
                return deal_host_carrier_reject(
                    deal_descriptor_intern("unknown"), "array",
                    &crossing->origin);
            }
            deal_error *cause = deal_host_materialize(crossing, inner, memo,
                &value);
            if (cause != NULL) {
                return cause;
            }
        } else {
            value = deal_host_datum_of_referent(referent);
        }
        deal_host_keep(crossing, deal_data_referent(&value));
        deal_container_table_write(table, key, &value);
    }
    *out = created;
    return NULL;
}

static deal_error *deal_host_materialize_error(deal_host_crossing *crossing,
                                               deal_host_box *box,
                                               deal_host_memo *memo,
                                               deal_data *out) {
    /* The builtin Error's two declared fields are `code` and `message`, both
     * `string` (C3/R2), so each host-supplied member is validated at that
     * declared position in declaration order, exactly as a declared class's
     * required fields are (H2/R11). A required field the host omitted renders
     * its own no-value host arm; the platform fault stays reserved for a
     * genuinely malformed protocol shape. */
    static const char *const names[] = {"code", "message"};
    const size_t field_count = sizeof names / sizeof names[0];
    deal_descriptor *declared = deal_descriptor_intern("string");
    deal_check_input checked[sizeof names / sizeof names[0]];
    memset(checked, 0, sizeof checked);
    for (size_t position = 0; position < field_count; position += 1u) {
        const deal_host_member *member =
            deal_host_class_member(box, names[position]);
        if (member == NULL) {
            deal_check_input missing;
            memset(&missing, 0, sizeof missing);
            missing.presentation = DEAL_PRESENT_MISSING;
            deal_check_context context;
            context.origin = &crossing->origin;
            context.parameter_index = 0;
            deal_outcome failure = deal_check_value(declared, missing,
                DEAL_BOUNDARY_HOST_SYNC_RETURN, &context);
            if (failure.error != NULL) {
                return failure.error;
            }
            const char *expected = deal_descriptor_text(declared);
            deal_arm_parameter parameter = {"expected", expected};
            return deal_error_materialize(DEAL_ARM_ID_HOST_SYNC_RETURN_NOTHING,
                &parameter, 1u, expected, "nothing", &crossing->origin, NULL);
        }
        if (deal_host_is_untyped_array(crossing, member->value)) {
            return deal_host_carrier_reject(declared, "array",
                &crossing->origin);
        }
        deal_check_input *presented = &checked[position];
        deal_error *cause = NULL;
        if (deal_host_classify_into(crossing, member->value, memo, presented,
                &cause) != DEAL_OK) {
            if (cause != NULL) {
                return cause;
            }
            deal_platform_fault("host: a builtin Error field names a foreign"
                                " carrier");
        }
        if (presented->presentation == DEAL_PRESENT_MISSING) {
            /* The absence marker is presence metadata, never a required
             * field's datum (R2/R11). */
            return deal_host_carrier_reject(declared, "nil",
                &crossing->origin);
        }
        deal_check_context context;
        context.origin = &crossing->origin;
        context.parameter_index = 0;
        deal_outcome admitted = deal_check_value(declared, *presented,
            DEAL_BOUNDARY_HOST_SYNC_RETURN, &context);
        if (admitted.error != NULL) {
            return admitted.error;
        }
        if (presented->presentation != DEAL_PRESENT_DATUM) {
            return deal_host_carrier_reject(declared,
                deal_check_token(presented), &crossing->origin);
        }
    }
    if (checked[0].datum.tag != DEAL_DATA_STRING
            || checked[1].datum.tag != DEAL_DATA_STRING
            || checked[0].datum.reference == NULL
            || checked[1].datum.reference == NULL) {
        deal_platform_fault("host: a builtin Error field is not a string");
    }
    deal_error *error = deal_error_create_strings(
        (deal_string *)checked[0].datum.reference,
        (deal_string *)checked[1].datum.reference, NULL);
    deal_host_keep(crossing, error);
    deal_data created = deal_data_of_reference(DEAL_DATA_ERROR, error);
    deal_host_memo_record(memo, box, created);
    *out = created;
    return NULL;
}

static deal_error *deal_host_materialize_class(deal_host_crossing *crossing,
                                               deal_host_box *box,
                                               deal_host_memo *memo,
                                               deal_data *out) {
    const char *atom = box->bytes;
    if (atom == NULL) {
        deal_platform_fault("host: a class carrier without its atom");
    }
    if (strcmp(atom, "Error") == 0) {
        return deal_host_materialize_error(crossing, box, memo, out);
    }
    const deal_host_class_access *access = deal_host_class_access_of(atom);
    if (access == NULL) {
        deal_platform_fault("host: no emitted layout for a host-built class");
    }
    void *instance = NULL;
    deal_outcome created = access->create(access->context, &instance);
    if (created.error != NULL) {
        return created.error;
    }
    deal_host_keep(crossing, instance);
    deal_data value = deal_data_of_reference(DEAL_DATA_CLASS, instance);
    deal_host_memo_record(memo, box, value);
    /* Declared fields are validated in declaration order (H2/R11). A present
     * field's own descriptor check runs before the store, so a required
     * present-undefined, a wrong-kind value or malformed text selects the
     * crossing's host cell arm and never lands in the typed slot. */
    for (size_t position = 0; position < access->declared->field_count;
            position += 1u) {
        const deal_class_field *field = &access->declared->fields[position];
        const deal_host_member *member =
            deal_host_class_member(box, field->name);
        if (member == NULL) {
            if (field->optional != 0) {
                continue;
            }
            /* Required fields must be present even when undefined is admitted
             * (R2): the field's own descriptor check first, then its no-value
             * arm for a type that would otherwise admit the absent marker. */
            deal_check_input missing;
            memset(&missing, 0, sizeof missing);
            missing.presentation = DEAL_PRESENT_MISSING;
            deal_check_context context;
            context.origin = &crossing->origin;
            context.parameter_index = 0;
            deal_outcome failure = deal_check_value(field->descriptor, missing,
                DEAL_BOUNDARY_HOST_SYNC_RETURN, &context);
            if (failure.error != NULL) {
                return failure.error;
            }
            const char *expected = deal_descriptor_text(field->descriptor);
            deal_arm_parameter parameter = {"expected", expected};
            return deal_error_materialize(
                DEAL_ARM_ID_HOST_SYNC_RETURN_NOTHING, &parameter, 1u, expected,
                "nothing", &crossing->origin, NULL);
        }
        if (deal_host_is_untyped_array(crossing, member->value)) {
            return deal_host_carrier_reject(field->descriptor, "array",
                &crossing->origin);
        }
        deal_check_input checked;
        deal_error *cause = NULL;
        if (deal_host_classify_into(crossing, member->value, memo, &checked,
                &cause) != DEAL_OK) {
            if (cause != NULL) {
                return cause;
            }
            deal_platform_fault("host: class field names a foreign carrier");
        }
        if (checked.presentation == DEAL_PRESENT_MISSING) {
            /* A host field carrier that is the absence marker is not data
             * (R2/R11): the field's own declared slot rejects it before the
             * descriptor check, whose MISSING -> undefined normalization belongs
             * to an absent read. */
            return deal_host_carrier_reject(field->descriptor, "nil",
                &crossing->origin);
        }
        deal_check_context context;
        context.origin = &crossing->origin;
        context.parameter_index = 0;
        deal_outcome admitted = deal_check_value(field->descriptor, checked,
            DEAL_BOUNDARY_HOST_SYNC_RETURN, &context);
        if (admitted.error != NULL) {
            return admitted.error;
        }
        if (checked.presentation != DEAL_PRESENT_DATUM) {
            /* `nothing` and the other diagnostic presentations are not data
             * either, so the field rejects them with the same composite arm. */
            return deal_host_carrier_reject(field->descriptor,
                deal_check_token(&checked), &crossing->origin);
        }
        deal_outcome stored = access->store(access->context, instance,
            position, &checked, 1);
        if (stored.error != NULL) {
            return stored.error;
        }
    }
    /* The declared metadata is the authority (H2/H7): a field the class does not
     * declare is the class-construction rejection. */
    for (size_t index = 0; index < box->member_count; index += 1u) {
        const deal_host_member *member = &box->members[index];
        if (deal_host_class_field_index(access->declared,
                member->key_bytes) >= 0) {
            continue;
        }
        deal_arm_parameter parameter[2] = {
            {"field", member->key_bytes}, {"classId", atom}
        };
        return deal_error_materialize(DEAL_ARM_ID_CLASS_EXTRA_FIELD,
            parameter, 2u, NULL, NULL, &crossing->origin, NULL);
    }
    *out = value;
    return NULL;
}

/* The thunk of one host-created function value (H4): the host function is
 * invoked with the crossing carriers of the checked argument presentations and
 * its returned carrier is materialized. A host failure becomes the carried
 * Error; a relayed DEAL failure is the identical Error. */
static deal_outcome_data deal_host_function_thunk(void *function,
                                                  const deal_check_input *arguments,
                                                  uint32_t argument_count,
                                                  const deal_origin *origin);

static deal_error *deal_host_materialize_function(deal_host_crossing *crossing,
                                                  deal_host_box *box,
                                                  deal_host_memo *memo,
                                                  deal_data *out) {
    if (box->function == NULL || box->signature == NULL) {
        deal_platform_fault("host: a function carrier without its entry");
    }
    /* The wrapper object holds the host function; it is one managed object of
     * the host group's own layout, so the function value's capture cell roots it
     * and the collector walks it as a reference-free object (M1, M5). */
    deal_host_layouts_ensure();
    void *wrapper = deal_alloc(DEAL_LAYOUT_HOST_FUNCTION,
        sizeof(deal_host_function));
    memcpy(wrapper, &box->function, sizeof(deal_host_function));
    deal_host_keep(crossing, wrapper);
    const void *cells[1];
    cells[0] = wrapper;
    deal_fn *created = deal_fn_create(deal_host_function_thunk, cells, 1u,
        box->signature, box->is_async);
    deal_host_keep(crossing, created);
    deal_data value = deal_data_of_reference(DEAL_DATA_FUNCTION, created);
    deal_host_memo_record(memo, box, value);
    *out = value;
    return NULL;
}

static deal_error *deal_host_materialize(deal_host_crossing *crossing,
                                         deal_host_box *box,
                                         deal_host_memo *memo,
                                         deal_data *out) {
    deal_data *existing = deal_host_memo_lookup(memo, box);
    if (existing != NULL) {
        *out = *existing;
        return NULL;
    }
    switch (box->kind) {
    case DEAL_HOST_BOX_NULL:
        *out = deal_data_of_null();
        return NULL;
    case DEAL_HOST_BOX_UNDEFINED:
        *out = deal_data_of_undefined();
        return NULL;
    case DEAL_HOST_BOX_BOOLEAN:
        *out = deal_data_of_boolean(box->boolean);
        return NULL;
    case DEAL_HOST_BOX_NUMBER:
        *out = deal_data_of_number(box->number);
        return NULL;
    case DEAL_HOST_BOX_STRING: {
        deal_string *text = deal_text_new(box->bytes, box->length);
        deal_host_keep(crossing, text);
        deal_data created = deal_data_of_reference(DEAL_DATA_STRING, text);
        deal_host_memo_record(memo, box, created);
        *out = created;
        return NULL;
    }
    case DEAL_HOST_BOX_ARRAY:
        if (box->element_descriptor == NULL) {
            /* The untyped negative presentation: one managed array that records
             * no element descriptor, so every declared array position rejects it
             * and no element is ever admitted (H2/R11). */
            deal_array *untyped = deal_container_array_new(
                DEAL_LAYOUT_ELEMENT_DATUM, 0u);
            deal_host_keep(crossing, untyped);
            deal_data created = deal_data_of_reference(DEAL_DATA_ARRAY, untyped);
            deal_host_memo_record(memo, box, created);
            *out = created;
            return NULL;
        }
        return deal_host_materialize_array(crossing, box, memo, out);
    case DEAL_HOST_BOX_RECORD:
        return deal_host_materialize_record(crossing, box, memo, out);
    case DEAL_HOST_BOX_CLASS:
        return deal_host_materialize_class(crossing, box, memo, out);
    case DEAL_HOST_BOX_FUNCTION:
        return deal_host_materialize_function(crossing, box, memo, out);
    case DEAL_HOST_BOX_MISSING:
    case DEAL_HOST_BOX_NOTHING:
    case DEAL_HOST_BOX_ASYNC_OPERATION:
    case DEAL_HOST_BOX_INVALID_UNICODE:
    case DEAL_HOST_BOX_CLASS_ATOM:
        deal_platform_fault("host: a non-data presentation reached"
                            " materialization");
    }
    deal_platform_fault("host: boxed kind outside the closed set");
    return NULL;
}

/* =========================================================================
 * The typed presentation of one carrier
 * ========================================================================= */

/* The check presentation of one carrier: a managed view keeps the datum form
 * and a boxed carrier is materialized (R11), so the one descriptor-driven check
 * sees the persistent representation DEAL holds. A failed materialization of a
 * returned carrier answers its own carried Error through `cause`, so a crossing
 * reports the typed-boundary arm instead of an infrastructure fault. */
static deal_status deal_host_classify_into(deal_host_crossing *crossing,
                                           deal_value value,
                                           deal_host_memo *memo,
                                           deal_check_input *out,
                                           deal_error **cause) {
    if (cause != NULL) {
        *cause = NULL;
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    memset(out, 0, sizeof(*out));
    if (box == NULL) {
        out->presentation = DEAL_PRESENT_DATUM;
        out->datum = deal_host_datum_of_referent(referent);
        if (deal_host_kind_of_object(referent) == DEAL_KIND_FUNCTION) {
            out->signature = deal_fn_signature(referent);
        }
        if (deal_host_kind_of_object(referent) == DEAL_KIND_CLASS) {
            /* The carried class atom of a managed instance, so a class-typed
             * position reads the instance's own nominal identity (R2/R4). */
            const char *atom = NULL;
            (void)deal_host_class_access_of_instance(referent, &atom);
            out->class_atom = atom;
        }
        return DEAL_OK;
    }
    switch (box->kind) {
    case DEAL_HOST_BOX_MISSING:
        out->presentation = DEAL_PRESENT_MISSING;
        return DEAL_OK;
    case DEAL_HOST_BOX_NOTHING:
        out->presentation = DEAL_PRESENT_NOTHING;
        return DEAL_OK;
    case DEAL_HOST_BOX_ASYNC_OPERATION:
        out->presentation = DEAL_PRESENT_ASYNC_OPERATION;
        return DEAL_OK;
    case DEAL_HOST_BOX_INVALID_UNICODE:
        out->presentation = DEAL_PRESENT_INVALID_UNICODE;
        out->unicode_defect = box->unicode_defect;
        return DEAL_OK;
    case DEAL_HOST_BOX_NULL:
        out->presentation = DEAL_PRESENT_DATUM;
        out->datum = deal_data_of_null();
        return DEAL_OK;
    case DEAL_HOST_BOX_UNDEFINED:
        out->presentation = DEAL_PRESENT_DATUM;
        out->datum = deal_data_of_undefined();
        return DEAL_OK;
    case DEAL_HOST_BOX_BOOLEAN:
        out->presentation = DEAL_PRESENT_DATUM;
        out->datum = deal_data_of_boolean(box->boolean);
        return DEAL_OK;
    case DEAL_HOST_BOX_NUMBER:
        out->presentation = DEAL_PRESENT_DATUM;
        out->datum = deal_data_of_number(box->number);
        return DEAL_OK;
    case DEAL_HOST_BOX_STRING: {
        uint32_t defect = 0u;
        if (!deal_host_bytes_are_scalars(box->bytes, box->length, &defect)) {
            out->presentation = DEAL_PRESENT_INVALID_UNICODE;
            out->unicode_defect = defect;
            return DEAL_OK;
        }
        deal_string *text = deal_text_new(box->bytes, box->length);
        deal_host_keep(crossing, text);
        out->presentation = DEAL_PRESENT_DATUM;
        out->datum = deal_data_of_reference(DEAL_DATA_STRING, text);
        return DEAL_OK;
    }
    case DEAL_HOST_BOX_ARRAY:
    case DEAL_HOST_BOX_RECORD:
    case DEAL_HOST_BOX_CLASS:
    case DEAL_HOST_BOX_FUNCTION: {
        deal_data datum = deal_data_of_undefined();
        deal_error *failure = deal_host_materialize(crossing, box, memo,
            &datum);
        if (failure != NULL) {
            if (cause != NULL) {
                *cause = failure;
            }
            return DEAL_STATUS_KIND;
        }
        out->presentation = DEAL_PRESENT_DATUM;
        out->datum = datum;
        if (box->kind == DEAL_HOST_BOX_CLASS) {
            out->class_atom = box->bytes;
        } else if (box->kind == DEAL_HOST_BOX_FUNCTION) {
            out->signature = box->signature;
        }
        return DEAL_OK;
    }
    case DEAL_HOST_BOX_CLASS_ATOM:
        return DEAL_STATUS_KIND;
    }
    deal_platform_fault("host: boxed kind outside the closed set");
    return DEAL_STATUS_KIND;
}

/* The same classification for a position that only answers a status (a store
 * into a view or an apply argument): a materialization failure is the closed
 * `kind` status there, never a fabricated value. A host `missing` carrier is
 * the absence marker, never a datum, so every such position rejects it as a
 * kind status (R2/R11): the shared check's MISSING -> undefined normalization is
 * the absent-read projection and never applies to a host carrier. */
static deal_status deal_host_classify(deal_host_crossing *crossing,
                                      deal_value value,
                                      deal_host_memo *memo,
                                      deal_check_input *out) {
    /* The untyped-array negative presentation is never data at any position
     * that answers a status (H2/R11): a view store, an apply argument and a
     * re-entry argument are all rejected as the closed kind status. */
    if (deal_host_is_untyped_array(crossing, value)) {
        return DEAL_STATUS_KIND;
    }
    deal_status status = deal_host_classify_into(crossing, value, memo, out,
        NULL);
    if (status == DEAL_OK && out->presentation == DEAL_PRESENT_MISSING) {
        return DEAL_STATUS_KIND;
    }
    return status;
}

/* One carrier for one admitted presentation (parameter positions). */
static deal_status deal_host_carrier_of_input(const deal_check_input *input,
                                              deal_value *out) {
    switch (input->presentation) {
    case DEAL_PRESENT_DATUM:
        return deal_host_datum_carrier(input->datum, out);
    case DEAL_PRESENT_MISSING:
    case DEAL_PRESENT_NOTHING:
    case DEAL_PRESENT_ASYNC_OPERATION: {
        deal_host_crossing *crossing = deal_host_crossing_current();
        if (crossing == NULL) {
            return DEAL_STATUS_OUT_OF_SCOPE;
        }
        deal_host_box_kind kind = input->presentation == DEAL_PRESENT_MISSING
            ? DEAL_HOST_BOX_MISSING
            : input->presentation == DEAL_PRESENT_NOTHING
                ? DEAL_HOST_BOX_NOTHING
                : DEAL_HOST_BOX_ASYNC_OPERATION;
        deal_host_box *box = deal_host_box_new(crossing, kind);
        return deal_host_box_publish(box, out);
    }
    case DEAL_PRESENT_INVALID_UNICODE:
        deal_platform_fault("host: an invalid-Unicode presentation reached a"
                            " parameter position");
    }
    deal_platform_fault("host: presentation outside the closed set");
    return DEAL_STATUS_KIND;
}

/* =========================================================================
 * The failure channel (H2, R5)
 * ========================================================================= */

static deal_error_token deal_host_token_issue(deal_host_crossing *crossing,
                                              deal_error *error) {
    deal_host_token_counter += 1u;
    if (deal_host_token_counter == 0u) {
        deal_host_token_counter = 1u;
    }
    crossing->tokens = deal_host_grow(crossing->tokens,
        &crossing->token_capacity, crossing->token_count,
        sizeof(*crossing->tokens),
        "host: error-token table allocation failed");
    deal_host_token_entry *entry = &crossing->tokens[crossing->token_count];
    entry->token = deal_host_token_counter;
    entry->error = error;
    entry->consumed = 0;
    crossing->token_count += 1u;
    return entry->token;
}

/* The live entry of one token in this crossing, or NULL. */
static deal_host_token_entry *deal_host_token_find(deal_host_crossing *crossing,
                                                   deal_error_token token) {
    if (crossing == NULL || token == 0u) {
        return NULL;
    }
    for (size_t index = 0; index < crossing->token_count; index += 1u) {
        if (crossing->tokens[index].token == token) {
            return &crossing->tokens[index];
        }
    }
    return NULL;
}

/* The declared result atom of one canonical function signature, through the one
 * descriptor parser (R4): the parsed result position, `void` included. The
 * signature is a canonical function descriptor, so a text outside that grammar
 * is the caller's producer defect. */
static deal_descriptor *deal_host_signature_result(const char *signature) {
    if (signature == NULL || !deal_host_descriptor_is_function(signature)) {
        deal_platform_fault("host: a crossing without its declared signature");
    }
    return deal_descriptor_result(deal_descriptor_intern(signature));
}

deal_outcome_reference deal_host_export_value(const deal_host_export *exported,
                                              const char *signature_descriptor,
                                              const deal_origin *origin) {
    deal_outcome_reference outcome;
    outcome.error = NULL;
    outcome.value = NULL;
    if (exported == NULL || origin == NULL) {
        deal_platform_fault("host: malformed export-value call");
    }
    if (exported->kind != DEAL_HOST_EXPORT_FUNCTION
            || exported->function == NULL) {
        deal_platform_fault("host: the export value of a non-function export");
    }
    if (signature_descriptor == NULL
            || !deal_host_descriptor_is_function(signature_descriptor)) {
        deal_platform_fault("host: the export value of a malformed signature");
    }
    deal_host_layouts_ensure();
    /* The wrapper is one managed object that only the created function value
     * will reference (M5): this helper roots it across the allocating
     * `deal_fn_create`, so a collection at that safepoint cannot reclaim it. */
    deal_root_scope_push();
    void *wrapper = deal_alloc(DEAL_LAYOUT_HOST_FUNCTION,
        sizeof(deal_host_function));
    uint32_t held = deal_root_scope_hold(wrapper);
    memcpy(wrapper, &exported->function, sizeof(deal_host_function));
    const void *cells[1];
    cells[0] = wrapper;
    deal_fn *created = deal_fn_create(deal_host_function_thunk, cells, 1u,
        signature_descriptor, 0);
    deal_root_scope_release(held);
    deal_root_scope_pop();
    outcome.value = created;
    return outcome;
}

deal_status deal_host_relay_error(deal_error *error, deal_host_result *out) {
    if (out == NULL || error == NULL) {
        deal_platform_fault("host: malformed failure relay");
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_value carrier;
    deal_status status = deal_host_issue_view(error, &carrier);
    if (status != DEAL_OK) {
        return status;
    }
    out->failed = 1;
    out->value = carrier;
    out->failure.kind = DEAL_FAILURE_DEAL_ERROR;
    out->failure.code = NULL;
    out->failure.message = NULL;
    out->failure.expected = NULL;
    out->failure.actual = NULL;
    out->failure.error = deal_host_token_issue(crossing, error);
    return DEAL_OK;
}

deal_status deal_failure_discard(deal_error_token token) {
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_token_entry *entry = deal_host_token_find(crossing, token);
    if (entry == NULL || entry->consumed != 0) {
        return DEAL_STATUS_PROTOCOL;
    }
    entry->consumed = 1;
    return DEAL_OK;
}

/* Admits one host failure record as the Error that propagates (R5). */
static deal_error *deal_host_admit_failure(deal_host_crossing *crossing,
                                           const deal_host_failure *failure) {
    if (failure->kind == DEAL_FAILURE_HOST_TEXT) {
        if (failure->code == NULL || failure->message == NULL) {
            deal_host_protocol("a host-authored failure carries no text");
        }
        if (failure->error != 0u) {
            deal_host_protocol("a host-authored failure carries an error"
                               " token");
        }
        return deal_error_host_text(failure->code, failure->message,
            failure->expected, failure->actual);
    }
    if (failure->kind != DEAL_FAILURE_DEAL_ERROR) {
        deal_host_protocol("a host failure record has an unknown kind");
    }
    deal_host_token_entry *entry = deal_host_token_find(crossing,
        failure->error);
    if (entry == NULL || entry->consumed != 0) {
        deal_host_protocol("a host relayed an error token this crossing never"
                           " issued");
    }
    if (failure->code != NULL || failure->message != NULL) {
        deal_host_protocol("a relayed DEAL failure carries host text");
    }
    entry->consumed = 1;
    return entry->error;
}

/* =========================================================================
 * deal_apply and the host-created function value (H2, H4)
 * ========================================================================= */

static deal_outcome_data deal_host_function_thunk(void *function,
                                                  const deal_check_input *arguments,
                                                  uint32_t argument_count,
                                                  const deal_origin *origin) {
    deal_outcome_data outcome;
    outcome.error = NULL;
    outcome.value = deal_data_of_undefined();
    if (function == NULL) {
        deal_platform_fault("host: a function thunk without its carrier");
    }
    void *wrapper = deal_fn_capture(function, 0u);
    if (wrapper == NULL) {
        deal_platform_fault("host: a host function value without its wrapper");
    }
    deal_host_function host = NULL;
    memcpy(&host, wrapper, sizeof(host));
    if (host == NULL) {
        deal_platform_fault("host: a host function value without its entry");
    }
    /* The function value's own canonical signature is the crossing's declared
     * position set (H4): its parameters are checked at their
     * `HOST_PARAMETER` boundaries and its result at the return boundary, so a
     * host that applies the value itself never bypasses the declared checks. */
    deal_descriptor *signature = deal_descriptor_intern(
        deal_fn_signature(function));
    size_t parameter_count = 0u;
    deal_descriptor *const *parameters =
        deal_descriptor_parameters(signature, &parameter_count);
    deal_descriptor *declared_result = deal_descriptor_result(signature);
    if ((size_t)argument_count != parameter_count) {
        deal_platform_fault("host: a function value applied with an argument"
                            " count its own signature does not declare");
    }
    deal_host_crossing *crossing = deal_host_crossing_push(origin);
    const deal_origin *crossing_origin = origin != NULL
        ? origin : &crossing->origin;
    for (uint32_t index = 0; index < argument_count; index += 1u) {
        deal_check_context context;
        context.origin = crossing_origin;
        context.parameter_index = (int32_t)index + 1;
        deal_outcome admitted = deal_check_value(parameters[index],
            arguments[index], DEAL_BOUNDARY_HOST_PARAMETER, &context);
        if (admitted.error != NULL) {
            deal_host_crossing_pop(crossing);
            outcome.error = admitted.error;
            return outcome;
        }
    }
    deal_value *carriers = NULL;
    if (argument_count != 0u) {
        carriers = deal_platform_allocate(deal_platform_checked_multiply(
            argument_count, sizeof(*carriers)));
        if (carriers == NULL) {
            deal_platform_fault("host: argument carrier allocation failed");
        }
    }
    for (uint32_t index = 0; index < argument_count; index += 1u) {
        if (deal_host_carrier_of_input(&arguments[index], &carriers[index])
                != DEAL_OK) {
            deal_platform_fault("host: argument position outside the declared"
                                " domain");
        }
    }
    deal_host_result result = host(carriers, argument_count);
    deal_platform_release(carriers);
    if (result.failed != 0) {
        outcome.error = deal_host_admit_failure(crossing, &result.failure);
        deal_host_crossing_require_tokens_consumed(crossing);
        deal_host_crossing_pop(crossing);
        return outcome;
    }
    deal_host_crossing_require_tokens_consumed(crossing);
    int resultIsVoid = deal_descriptor_type(declared_result)
        == DEAL_DESCRIPTOR_VOID;
    if (!resultIsVoid
            && deal_host_is_untyped_array(crossing, result.value)) {
        /* The untyped test presentation is never DEAL data (H2/R11), exactly as
         * at the synchronous crossing's return position. */
        outcome.error = deal_host_carrier_reject(declared_result, "array",
            crossing_origin);
        deal_host_crossing_pop(crossing);
        return outcome;
    }
    deal_host_memo memo = {0};
    deal_check_input checked;
    deal_error *cause = NULL;
    if (deal_host_classify_into(crossing, result.value, &memo, &checked,
            &cause) != DEAL_OK) {
        if (cause != NULL) {
            deal_host_memo_release(&memo);
            deal_host_crossing_pop(crossing);
            outcome.error = cause;
            return outcome;
        }
        deal_platform_fault("host: a host function returned a foreign carrier");
    }
    if (!resultIsVoid && checked.presentation == DEAL_PRESENT_MISSING) {
        /* The host answered the absence marker at a declared data position:
         * absence is presence metadata, never a datum (R2/R11). */
        outcome.error = deal_host_carrier_reject(declared_result, "nil",
            crossing_origin);
        deal_host_memo_release(&memo);
        deal_host_crossing_pop(crossing);
        return outcome;
    }
    /* The declared result boundary of the function value's own signature runs
     * before the completion is answered (H2/H4), whatever the declared kind of
     * the result position is. */
    deal_check_context context;
    context.origin = crossing_origin;
    context.parameter_index = 0;
    deal_outcome admitted = deal_check_value(declared_result, checked,
        DEAL_BOUNDARY_HOST_SYNC_RETURN, &context);
    if (admitted.error != NULL) {
        deal_host_memo_release(&memo);
        deal_host_crossing_pop(crossing);
        outcome.error = admitted.error;
        return outcome;
    }
    if (checked.presentation == DEAL_PRESENT_NOTHING
            || checked.presentation == DEAL_PRESENT_MISSING) {
        outcome.value = deal_data_of_undefined();
    } else if (checked.presentation != DEAL_PRESENT_DATUM) {
        deal_platform_fault("host: a host function returned no data");
    } else {
        outcome.value = checked.datum;
    }
    deal_host_memo_release(&memo);
    deal_host_crossing_pop(crossing);
    return outcome;
}

deal_status deal_apply(deal_value function, const deal_value *args, size_t argc,
                       deal_host_result *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (args == NULL && argc != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(function, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL || referent == NULL
            || deal_host_kind_of_object(referent) != DEAL_KIND_FUNCTION) {
        return DEAL_STATUS_KIND;
    }
    /* The function value's own declared signature is the applied crossing's
     * declaration (H4): an argument count it does not declare is the closed
     * protocol status, so the host never reaches an entry with a blind count. */
    size_t declared_parameters = 0u;
    (void)deal_descriptor_parameters(
        deal_descriptor_intern(deal_fn_signature(referent)),
        &declared_parameters);
    if (argc != declared_parameters) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_memo memo = {0};
    deal_check_input *checked = NULL;
    if (argc != 0u) {
        checked = deal_platform_allocate(deal_platform_checked_multiply(
            argc, sizeof(*checked)));
        if (checked == NULL) {
            deal_platform_fault("host: apply argument allocation failed");
        }
    }
    for (size_t index = 0; index < argc; index += 1u) {
        if (deal_host_classify(crossing, args[index], &memo, &checked[index])
                != DEAL_OK) {
            deal_platform_release(checked);
            deal_host_memo_release(&memo);
            return DEAL_STATUS_KIND;
        }
    }
    deal_outcome_data applied = deal_fn_apply(referent, checked,
        (uint32_t)argc, &crossing->origin);
    deal_platform_release(checked);
    if (applied.error != NULL) {
        out->failed = 1;
        out->failure.kind = DEAL_FAILURE_DEAL_ERROR;
        out->failure.code = NULL;
        out->failure.message = NULL;
        out->failure.expected = NULL;
        out->failure.actual = NULL;
        out->failure.error = deal_host_token_issue(crossing, applied.error);
        status = deal_host_issue_view(applied.error, &out->value);
        deal_host_memo_release(&memo);
        return status;
    }
    out->failed = 0;
    memset(&out->failure, 0, sizeof(out->failure));
    status = deal_host_datum_carrier(applied.value, &out->value);
    deal_host_memo_release(&memo);
    return status;
}

/* =========================================================================
 * The host-driven re-entry (H4 `CALLBACK_INVOKE`)
 * ========================================================================= */

deal_status deal_host_callback_invoke(deal_host_result *out,
                                      deal_host_boxed_thunk thunk,
                                      void *function,
                                      const deal_value *arguments,
                                      size_t argument_count,
                                      const deal_origin *origin) {
    if (out == NULL || thunk == NULL) {
        deal_platform_fault("host: malformed host-driven re-entry");
    }
    if (arguments == NULL && argument_count != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    /* The re-entry is one crossing of the mutator's current call: the emitted
     * entry is the only path by which a host re-enters DEAL code, and a host
     * only runs inside a crossing (H2, H4). */
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    if (origin == NULL) {
        deal_platform_fault("host: a host-driven re-entry without its origin");
    }
    deal_host_memo memo = {0};
    deal_check_input *checked = NULL;
    if (argument_count != 0u) {
        checked = deal_platform_allocate(deal_platform_checked_multiply(
            argument_count, sizeof(*checked)));
        if (checked == NULL) {
            deal_platform_fault("host: re-entry argument allocation failed");
        }
    }
    for (size_t index = 0; index < argument_count; index += 1u) {
        if (deal_host_classify(crossing, arguments[index], &memo,
                &checked[index]) != DEAL_OK) {
            deal_platform_release(checked);
            deal_host_memo_release(&memo);
            return DEAL_STATUS_KIND;
        }
    }
    /* The recorded boxed thunk unboxes the HOST_TO_DEAL parameter positions,
     * runs the body and boxes the result at the DEAL_TO_HOST return boundary,
     * so a free function value applied by a host and this entry share one
     * behavior (H4). */
    deal_outcome_data applied = thunk(function, checked,
        (uint32_t)argument_count, origin);
    deal_platform_release(checked);
    memset(out, 0, sizeof(*out));
    out->failure.kind = DEAL_FAILURE_HOST_TEXT;
    if (applied.error != NULL) {
        /* The failure channel of H2: one runtime-issued token naming the
         * carried Error and the class carrier of that very instance, so the
         * host may inspect or retain it and must relay or discard the token
         * inside this crossing. */
        deal_status relayed = deal_host_relay_error(applied.error, out);
        deal_host_memo_release(&memo);
        return relayed;
    }
    deal_status status = deal_host_datum_carrier(applied.value, &out->value);
    deal_host_memo_release(&memo);
    return status;
}

/* =========================================================================
 * The synchronous crossing (H2 `HostCallSync`)
 * ========================================================================= */

deal_outcome_data deal_host_invoke_sync(const deal_host_export *exported,
                                        const deal_check_input *arguments,
                                        uint32_t argument_count,
                                        const char *signature_descriptor,
                                        const deal_origin *origin) {
    deal_outcome_data outcome;
    outcome.error = NULL;
    outcome.value = deal_data_of_undefined();
    if (exported == NULL || exported->function == NULL
            || exported->kind != DEAL_HOST_EXPORT_FUNCTION
            || exported->is_async != 0) {
        deal_platform_fault("host: a synchronous call of a non-function export"
                            " record");
    }
    if (origin == NULL) {
        deal_platform_fault("host: a crossing without its origin");
    }
    if (arguments == NULL && argument_count != 0u) {
        deal_platform_fault("host: a crossing without its arguments");
    }
    /* The export's declared canonical signature (H1/H2, and the export entry
     * record's own descriptor field at the export surface) declares the result
     * position through the one descriptor parser: a function-typed result is
     * that function descriptor, and the result-only `void` is the no-value
     * position. The text is never re-parsed as a signature. */
    deal_descriptor *declared = deal_host_signature_result(signature_descriptor);
    int declares_data = deal_descriptor_type(declared) != DEAL_DESCRIPTOR_VOID;
    deal_host_crossing *crossing = deal_host_crossing_push(origin);
    deal_value *carriers = NULL;
    if (argument_count != 0u) {
        carriers = deal_platform_allocate(deal_platform_checked_multiply(
            argument_count, sizeof(*carriers)));
        if (carriers == NULL) {
            deal_platform_fault("host: argument carrier allocation failed");
        }
    }
    for (uint32_t index = 0; index < argument_count; index += 1u) {
        if (deal_host_carrier_of_input(&arguments[index], &carriers[index])
                != DEAL_OK) {
            deal_platform_fault("host: argument position outside the declared"
                                " domain");
        }
    }
    deal_host_result result = exported->function(carriers, argument_count);
    deal_platform_release(carriers);
    if (result.failed != 0) {
        deal_error *error = deal_host_admit_failure(crossing, &result.failure);
        deal_host_crossing_require_tokens_consumed(crossing);
        deal_host_crossing_pop(crossing);
        outcome.error = error;
        return outcome;
    }
    /* The token rule precedes the return check (H2). */
    deal_host_crossing_require_tokens_consumed(crossing);
    if (declares_data && deal_host_is_untyped_array(crossing, result.value)) {
        deal_error *rejected = deal_host_carrier_reject(declared, "array",
            origin);
        deal_host_crossing_pop(crossing);
        outcome.error = rejected;
        return outcome;
    }
    deal_host_memo memo = {0};
    deal_check_input checked;
    deal_error *cause = NULL;
    if (deal_host_classify_into(crossing, result.value, &memo, &checked,
            &cause) != DEAL_OK) {
        if (cause != NULL) {
            deal_host_memo_release(&memo);
            deal_host_crossing_pop(crossing);
            outcome.error = cause;
            return outcome;
        }
        deal_platform_fault("host: a host export returned a foreign carrier");
    }
    if (declares_data && checked.presentation == DEAL_PRESENT_MISSING) {
        /* The absence marker is presence metadata, never a datum, so no declared
         * data position admits it (R2/R11). The result-only `void` position
         * keeps the result protocol's no-value projection to undefined. */
        deal_error *rejected = deal_host_carrier_reject(declared, "nil", origin);
        deal_host_memo_release(&memo);
        deal_host_crossing_pop(crossing);
        outcome.error = rejected;
        return outcome;
    }
    deal_check_context context;
    context.origin = origin;
    context.parameter_index = 0;
    deal_outcome admitted = deal_check_value(declared, checked,
        DEAL_BOUNDARY_HOST_SYNC_RETURN, &context);
    if (admitted.error != NULL) {
        deal_host_memo_release(&memo);
        deal_host_crossing_pop(crossing);
        outcome.error = admitted.error;
        return outcome;
    }
    if (checked.presentation == DEAL_PRESENT_DATUM) {
        outcome.value = checked.datum;
    } else {
        /* A `nothing` return at a result-only position, and an admitted
         * `undefined` completion, project to the `undefined` datum (C4/H2). */
        outcome.value = deal_data_of_undefined();
    }
    /* Materialized graph nodes are crossing-rooted; the admitted value survives
     * because DEAL now holds it (M5). */
    deal_host_memo_release(&memo);
    deal_host_crossing_pop(crossing);
    return outcome;
}

/* =========================================================================
 * The value surface: construction, inspection and access (H2, R11)
 * ========================================================================= */

/* Issues one carrier for one persistent datum: a view for managed kinds and the
 * nullish singletons, a boxed carrier for scalars. */
static deal_status deal_host_datum_carrier(deal_data datum, deal_value *out) {
    switch ((deal_data_tag)datum.tag) {
    case DEAL_DATA_UNDEFINED:
        return deal_host_issue_view(deal_undefined_singleton, out);
    case DEAL_DATA_NULL:
        return deal_host_issue_view(deal_null_singleton, out);
    case DEAL_DATA_BOOLEAN:
        return deal_boolean(datum.number != 0.0 ? 1 : 0, out);
    case DEAL_DATA_NUMBER:
        return deal_number(datum.number, out);
    default:
        if (datum.reference == NULL) {
            return DEAL_STATUS_KIND;
        }
        return deal_host_issue_view(datum.reference, out);
    }
}

static deal_status deal_host_publish_box(deal_host_box_kind kind,
                                         deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    return deal_host_box_publish(deal_host_box_new(crossing, kind), out);
}

deal_status deal_null(deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    return deal_host_issue_view(deal_null_singleton, out);
}

deal_status deal_undefined(deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    return deal_host_issue_view(deal_undefined_singleton, out);
}

deal_status deal_nothing(deal_value *out) {
    return deal_host_publish_box(DEAL_HOST_BOX_NOTHING, out);
}

deal_status deal_absent(deal_value *out) {
    return deal_host_publish_box(DEAL_HOST_BOX_MISSING, out);
}

deal_status deal_boolean(int truth, deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_box *box = deal_host_box_new(crossing, DEAL_HOST_BOX_BOOLEAN);
    box->boolean = truth != 0 ? 1 : 0;
    return deal_host_box_publish(box, out);
}

deal_status deal_number(double value, deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_box *box = deal_host_box_new(crossing, DEAL_HOST_BOX_NUMBER);
    box->number = value;
    return deal_host_box_publish(box, out);
}

deal_status deal_string_new(const void *bytes, size_t length, deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (bytes == NULL && length != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_box *box = deal_host_box_new(crossing, DEAL_HOST_BOX_STRING);
    if (length != 0u) {
        box->bytes = deal_platform_allocate(length);
        if (box->bytes == NULL) {
            deal_platform_fault("host: string carrier allocation failed");
        }
        memcpy(box->bytes, bytes, length);
    }
    box->length = length;
    uint32_t defect = 0u;
    if (!deal_host_bytes_are_scalars(box->bytes, length, &defect)) {
        box->kind = DEAL_HOST_BOX_INVALID_UNICODE;
        box->unicode_defect = defect;
    }
    return deal_host_box_publish(box, out);
}

static deal_status deal_host_array_box(int64_t length,
                                       const char *elementDescriptor,
                                       deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (length < 0) {
        return DEAL_STATUS_BOUNDS;
    }
    if (elementDescriptor != NULL
            && !deal_host_descriptor_is_data(elementDescriptor)) {
        return DEAL_STATUS_BAD_DESCRIPTOR;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    /* The slot bytes are computed once, with the checked product (R10), before
     * the first allocation: a length whose element region cannot exist is the
     * deterministic resource fault, never a short block carrying a fabricated
     * element count. The same product is the memset extent. */
    size_t slot_bytes = deal_platform_checked_multiply((size_t)length,
        sizeof(deal_host_slot));
    deal_host_box *box = deal_host_box_new(crossing, DEAL_HOST_BOX_ARRAY);
    if (elementDescriptor != NULL) {
        box->element_descriptor = deal_descriptor_intern(elementDescriptor);
    }
    if (length != 0) {
        box->slots = deal_platform_allocate(slot_bytes);
        if (box->slots == NULL) {
            deal_platform_fault("host: array carrier allocation failed");
        }
        memset(box->slots, 0, slot_bytes);
        box->slot_capacity = (size_t)length;
    }
    box->slot_count = (size_t)length;
    return deal_host_box_publish(box, out);
}

deal_status deal_array_new(int64_t length, deal_value *out) {
    return deal_host_array_box(length, NULL, out);
}

deal_status deal_array_new_typed(int64_t length, const char *elementDescriptor,
                                deal_value *out) {
    if (elementDescriptor == NULL) {
        return DEAL_STATUS_BAD_DESCRIPTOR;
    }
    return deal_host_array_box(length, elementDescriptor, out);
}

deal_status deal_table_new(deal_value *out) {
    return deal_host_publish_box(DEAL_HOST_BOX_RECORD, out);
}

deal_status deal_class_atom(const char *descriptorText, deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (!deal_host_descriptor_is_class_atom(descriptorText)) {
        return DEAL_STATUS_BAD_DESCRIPTOR;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_box *box = deal_host_box_new(crossing, DEAL_HOST_BOX_CLASS_ATOM);
    size_t length = strlen(descriptorText);
    box->bytes = deal_platform_allocate(length + 1u);
    if (box->bytes == NULL) {
        deal_platform_fault("host: class atom allocation failed");
    }
    memcpy(box->bytes, descriptorText, length + 1u);
    box->length = length;
    return deal_host_box_publish(box, out);
}

deal_status deal_class_new(deal_value atom, deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(atom, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box == NULL || box->kind != DEAL_HOST_BOX_CLASS_ATOM) {
        return DEAL_STATUS_KIND;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    deal_host_box *created = deal_host_box_new(crossing, DEAL_HOST_BOX_CLASS);
    created->bytes = deal_platform_allocate(box->length + 1u);
    if (created->bytes == NULL) {
        deal_platform_fault("host: class carrier allocation failed");
    }
    memcpy(created->bytes, box->bytes, box->length + 1u);
    created->length = box->length;
    return deal_host_box_publish(created, out);
}

deal_status deal_function_new(deal_host_function fn,
                             const char *signatureDescriptor,
                             deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (fn == NULL) {
        return DEAL_STATUS_PROTOCOL;
    }
    if (signatureDescriptor == NULL
            || !deal_host_descriptor_is_function(signatureDescriptor)) {
        return DEAL_STATUS_BAD_DESCRIPTOR;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_box *box = deal_host_box_new(crossing, DEAL_HOST_BOX_FUNCTION);
    box->function = fn;
    size_t length = strlen(signatureDescriptor);
    box->bytes = deal_platform_allocate(length + 1u);
    if (box->bytes == NULL) {
        deal_platform_fault("host: function carrier allocation failed");
    }
    memcpy(box->bytes, signatureDescriptor, length + 1u);
    box->length = length;
    box->signature = box->bytes;
    box->is_async = signatureDescriptor[0] == 'a' ? 1 : 0;
    return deal_host_box_publish(box, out);
}

deal_status deal_kind_of(deal_value value, deal_value_kind *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    *out = box != NULL ? deal_host_kind_of_box(box)
                       : deal_host_kind_of_object(referent);
    return DEAL_OK;
}

deal_status deal_as_boolean(deal_value value, int *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box == NULL || box->kind != DEAL_HOST_BOX_BOOLEAN) {
        return DEAL_STATUS_KIND;
    }
    *out = box->boolean;
    return DEAL_OK;
}

deal_status deal_as_number(deal_value value, double *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box == NULL || box->kind != DEAL_HOST_BOX_NUMBER) {
        return DEAL_STATUS_KIND;
    }
    *out = box->number;
    return DEAL_OK;
}

deal_status deal_as_string(deal_value value, const void **bytes,
                          size_t *length) {
    if (bytes == NULL || length == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_STRING) {
            return DEAL_STATUS_KIND;
        }
        *bytes = box->bytes;
        *length = box->length;
        return DEAL_OK;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_STRING) {
        return DEAL_STATUS_KIND;
    }
    deal_string *text = referent;
    *bytes = deal_text_bytes(text);
    *length = deal_text_byte_length(text);
    return DEAL_OK;
}

deal_status deal_same(deal_value a, deal_value b, int *same) {
    if (same == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *left_box = NULL;
    void *left = NULL;
    deal_host_box *right_box = NULL;
    void *right = NULL;
    deal_status status = deal_host_resolve(a, &left_box, &left);
    if (status != DEAL_OK) {
        return status;
    }
    status = deal_host_resolve(b, &right_box, &right);
    if (status != DEAL_OK) {
        return status;
    }
    if (left_box != NULL || right_box != NULL) {
        /* Carrier identity for host-built values, matching the graph's `===`. */
        *same = left_box != NULL && left_box == right_box ? 1 : 0;
        return DEAL_OK;
    }
    *same = left == right ? 1 : 0;
    return DEAL_OK;
}

/* -- containers --------------------------------------------------------- */

deal_status deal_array_length(deal_value array, int64_t *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(array, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_ARRAY) {
            return DEAL_STATUS_KIND;
        }
        *out = (int64_t)box->slot_count;
        return DEAL_OK;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_ARRAY) {
        return DEAL_STATUS_KIND;
    }
    *out = (int64_t)deal_container_array_count(referent);
    return DEAL_OK;
}

/* Issues one carrier for one present element of a managed array. */
static deal_status deal_host_element_carrier(deal_host_crossing *crossing,
                                             uint32_t layout, const void *slot,
                                             deal_value *out) {
    (void)crossing;
    if (layout == DEAL_LAYOUT_ELEMENT_DATUM) {
        const deal_data *datum = slot;
        return deal_host_datum_carrier(*datum, out);
    }
    if (layout == DEAL_LAYOUT_ELEMENT_NULLISH_NUMBER) {
        const deal_nullish_number_element *element = slot;
        if (element->tag == DEAL_NULLISH_NULL) {
            return deal_host_issue_view(deal_null_singleton, out);
        }
        if (element->tag == DEAL_NULLISH_UNDEFINED) {
            return deal_host_issue_view(deal_undefined_singleton, out);
        }
        return deal_number(element->value, out);
    }
    if (layout == DEAL_LAYOUT_ELEMENT_NULLISH_BOOLEAN) {
        const deal_nullish_boolean_element *element = slot;
        if (element->tag == DEAL_NULLISH_NULL) {
            return deal_host_issue_view(deal_null_singleton, out);
        }
        if (element->tag == DEAL_NULLISH_UNDEFINED) {
            return deal_host_issue_view(deal_undefined_singleton, out);
        }
        return deal_boolean(element->value != 0u ? 1 : 0, out);
    }
    if (layout == DEAL_LAYOUT_ELEMENT_NUMBER) {
        double number = 0.0;
        memcpy(&number, slot, sizeof(number));
        return deal_number(number, out);
    }
    if (layout == DEAL_LAYOUT_ELEMENT_BOOLEAN) {
        uint8_t truth = 0u;
        memcpy(&truth, slot, sizeof(truth));
        return deal_boolean(truth != 0u ? 1 : 0, out);
    }
    void *referent = NULL;
    memcpy(&referent, slot, sizeof(referent));
    return deal_host_issue_view(referent, out);
}

deal_status deal_array_get(deal_value array, int64_t index, deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(array, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_ARRAY) {
            return DEAL_STATUS_KIND;
        }
        if (index < 0 || (size_t)index >= box->slot_count) {
            return DEAL_STATUS_BOUNDS;
        }
        deal_host_slot *slot = &box->slots[index];
        if (slot->present == 0) {
            return DEAL_STATUS_ABSENT;
        }
        return deal_host_slot_carrier(slot->value, out);
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_ARRAY) {
        return DEAL_STATUS_KIND;
    }
    deal_array *managed = referent;
    if (index < 0 || (size_t)index >= deal_container_array_count(managed)) {
        return DEAL_STATUS_BOUNDS;
    }
    return deal_host_element_carrier(deal_host_crossing_current(),
        deal_container_array_element_layout(managed),
        deal_container_array_element(managed, (size_t)index), out);
}

deal_status deal_array_set(deal_value array, int64_t index, deal_value element) {
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(array, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_ARRAY) {
            return DEAL_STATUS_KIND;
        }
        if (index < 0 || (size_t)index > box->slot_count) {
            return DEAL_STATUS_BOUNDS;
        }
        if ((size_t)index == box->slot_count) {
            box->slots = deal_host_grow(box->slots, &box->slot_capacity,
                box->slot_count, sizeof(*box->slots),
                "host: array carrier slot allocation failed");
            memset(&box->slots[box->slot_count], 0, sizeof(*box->slots));
            box->slot_count += 1u;
        }
        box->slots[index].present = 1;
        box->slots[index].value = element;
        return DEAL_OK;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_ARRAY) {
        return DEAL_STATUS_KIND;
    }
    deal_array *managed = referent;
    size_t count = deal_container_array_count(managed);
    if (index < 0 || (size_t)index > count) {
        return DEAL_STATUS_BOUNDS;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (deal_host_is_untyped_array(crossing, element)) {
        return DEAL_STATUS_KIND;
    }
    deal_host_memo memo = {0};
    deal_check_input checked;
    status = deal_host_classify(crossing, element, &memo, &checked);
    if (status != DEAL_OK) {
        deal_host_memo_release(&memo);
        return DEAL_STATUS_KIND;
    }
    if (checked.presentation != DEAL_PRESENT_DATUM) {
        deal_host_memo_release(&memo);
        return DEAL_STATUS_KIND;
    }
    uint32_t recorded = deal_container_array_element_descriptor(managed);
    if (recorded != 0u) {
        deal_descriptor *descriptor = deal_descriptor_of_reference(recorded);
        deal_check_context context;
        context.origin = &crossing->origin;
        context.parameter_index = 0;
        if (deal_check_value(descriptor, checked,
                deal_host_boundary_of(descriptor), &context).error != NULL) {
            deal_host_memo_release(&memo);
            return DEAL_STATUS_KIND;
        }
    }
    uint32_t layout = deal_container_array_element_layout(managed);
    deal_host_element staged;
    memset(&staged, 0, sizeof staged);
    deal_host_stage_element(layout, &checked.datum, &staged);
    if ((size_t)index == count) {
        deal_container_array_append(managed, &staged);
    } else {
        deal_container_array_store(managed, (size_t)index, &staged);
    }
    deal_host_memo_release(&memo);
    return DEAL_OK;
}

deal_status deal_table_get(deal_value table, const void *key, size_t keyLength,
                          deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (key == NULL && keyLength != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(table, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_RECORD) {
            return DEAL_STATUS_KIND;
        }
        for (size_t index = 0; index < box->member_count; index += 1u) {
            deal_host_member *member = &box->members[index];
            if (member->key_length == keyLength
                    && (keyLength == 0u
                        || memcmp(member->key_bytes, key, keyLength) == 0)) {
                return deal_host_slot_carrier(member->value, out);
            }
        }
        return DEAL_STATUS_ABSENT;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_TABLE) {
        return DEAL_STATUS_KIND;
    }
    uint32_t defect = 0u;
    if (!deal_host_bytes_are_scalars(key, keyLength, &defect)) {
        return DEAL_STATUS_KIND;
    }
    deal_string *name = deal_text_new(key, keyLength);
    deal_table *managed = referent;
    if (!deal_container_table_has(managed, name)) {
        return DEAL_STATUS_ABSENT;
    }
    return deal_host_datum_carrier(deal_container_table_read(managed, name),
        out);
}

deal_status deal_table_set(deal_value table, const void *key, size_t keyLength,
                          deal_value value) {
    if (key == NULL && keyLength != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(table, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    uint32_t defect = 0u;
    if (!deal_host_bytes_are_scalars(key, keyLength, &defect)) {
        return DEAL_STATUS_KIND;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_RECORD) {
            return DEAL_STATUS_KIND;
        }
        for (size_t index = 0; index < box->member_count; index += 1u) {
            deal_host_member *member = &box->members[index];
            if (member->key_length == keyLength
                    && (keyLength == 0u
                        || memcmp(member->key_bytes, key, keyLength) == 0)) {
                member->value = value;
                return DEAL_OK;
            }
        }
        box->members = deal_host_grow(box->members, &box->member_capacity,
            box->member_count, sizeof(*box->members),
            "host: record carrier member allocation failed");
        deal_host_member *member = &box->members[box->member_count];
        member->key_bytes = NULL;
        member->key_length = keyLength;
        if (keyLength != 0u) {
            member->key_bytes = deal_platform_allocate(keyLength);
            if (member->key_bytes == NULL) {
                deal_platform_fault("host: record carrier key allocation"
                                    " failed");
            }
            memcpy(member->key_bytes, key, keyLength);
        }
        member->value = value;
        box->member_count += 1u;
        return DEAL_OK;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_TABLE) {
        return DEAL_STATUS_KIND;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (deal_host_is_untyped_array(crossing, value)) {
        return DEAL_STATUS_KIND;
    }
    deal_host_memo memo = {0};
    deal_check_input checked;
    status = deal_host_classify(crossing, value, &memo, &checked);
    if (status != DEAL_OK) {
        deal_host_memo_release(&memo);
        return DEAL_STATUS_KIND;
    }
    /* A record member position is `unknown`, so the value must be supported DEAL
     * data (R11). */
    deal_check_context context;
    context.origin = &crossing->origin;
    context.parameter_index = 0;
    if (deal_check_value(deal_descriptor_intern("unknown"), checked,
            DEAL_BOUNDARY_TYPE_DESCRIPTOR, &context).error != NULL) {
        deal_host_memo_release(&memo);
        return DEAL_STATUS_KIND;
    }
    if (checked.presentation != DEAL_PRESENT_DATUM) {
        deal_host_memo_release(&memo);
        return DEAL_STATUS_KIND;
    }
    deal_string *name = deal_text_new(key, keyLength);
    deal_container_table_write(referent, name, &checked.datum);
    deal_host_memo_release(&memo);
    return DEAL_OK;
}

deal_status deal_table_has(deal_value table, const void *key, size_t keyLength,
                          int *present) {
    if (present == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (key == NULL && keyLength != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(table, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    uint32_t defect = 0u;
    if (!deal_host_bytes_are_scalars(key, keyLength, &defect)) {
        return DEAL_STATUS_KIND;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_RECORD) {
            return DEAL_STATUS_KIND;
        }
        for (size_t index = 0; index < box->member_count; index += 1u) {
            deal_host_member *member = &box->members[index];
            if (member->key_length == keyLength
                    && (keyLength == 0u
                        || memcmp(member->key_bytes, key, keyLength) == 0)) {
                *present = 1;
                return DEAL_OK;
            }
        }
        *present = 0;
        return DEAL_OK;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_TABLE) {
        return DEAL_STATUS_KIND;
    }
    deal_string *name = deal_text_new(key, keyLength);
    *present = deal_container_table_has(referent, name);
    return DEAL_OK;
}

deal_status deal_table_delete(deal_value table, const void *key,
                             size_t keyLength) {
    if (key == NULL && keyLength != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(table, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    uint32_t defect = 0u;
    if (!deal_host_bytes_are_scalars(key, keyLength, &defect)) {
        return DEAL_STATUS_KIND;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_RECORD) {
            return DEAL_STATUS_KIND;
        }
        for (size_t index = 0; index < box->member_count; index += 1u) {
            deal_host_member *member = &box->members[index];
            if (member->key_length != keyLength
                    || (keyLength != 0u
                        && memcmp(member->key_bytes, key, keyLength) != 0)) {
                continue;
            }
            deal_platform_release(member->key_bytes);
            for (size_t rest = index + 1u; rest < box->member_count;
                    rest += 1u) {
                box->members[rest - 1u] = box->members[rest];
            }
            box->member_count -= 1u;
            return DEAL_OK;
        }
        return DEAL_STATUS_ABSENT;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_TABLE) {
        return DEAL_STATUS_KIND;
    }
    deal_string *name = deal_text_new(key, keyLength);
    if (!deal_container_table_has(referent, name)) {
        return DEAL_STATUS_ABSENT;
    }
    deal_container_table_delete(referent, name);
    return DEAL_OK;
}

/* -- classes ------------------------------------------------------------ */

deal_status deal_class_identity(deal_value value, const char **descriptorText) {
    if (descriptorText == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_CLASS) {
            return DEAL_STATUS_KIND;
        }
        *descriptorText = box->bytes;
        return DEAL_OK;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_CLASS) {
        return DEAL_STATUS_KIND;
    }
    const char *atom = NULL;
    (void)deal_host_class_access_of_instance(referent, &atom);
    if (atom == NULL) {
        return DEAL_STATUS_UNKNOWN_CLASS;
    }
    *descriptorText = atom;
    return DEAL_OK;
}

/* The class instance one carrier names, or DEAL_STATUS_KIND. */
static deal_status deal_host_class_instance(deal_value value,
                                            deal_host_box **box_out,
                                            void **instance_out) {
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box == NULL) {
        if (deal_host_kind_of_object(referent) != DEAL_KIND_CLASS) {
            return DEAL_STATUS_KIND;
        }
    } else if (box->kind != DEAL_HOST_BOX_CLASS) {
        return DEAL_STATUS_KIND;
    }
    *box_out = box;
    *instance_out = referent;
    return DEAL_OK;
}

deal_status deal_field_get(deal_value object, const char *field,
                          deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (field == NULL) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *instance = NULL;
    deal_status status = deal_host_class_instance(object, &box, &instance);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        for (size_t index = 0; index < box->member_count; index += 1u) {
            if (deal_host_text_equal(box->members[index].key_bytes, field)) {
                return deal_host_slot_carrier(box->members[index].value, out);
            }
        }
        return DEAL_STATUS_ABSENT;
    }
    const char *atom = NULL;
    const deal_host_class_access *access =
        deal_host_class_access_of_instance(instance, &atom);
    if (access == NULL) {
        deal_error *error = instance;
        if (deal_host_text_equal(field, "code")) {
            deal_string *code = deal_error_code_value(error);
            return code == NULL ? DEAL_STATUS_ABSENT
                                : deal_host_issue_view(code, out);
        }
        if (deal_host_text_equal(field, "message")) {
            deal_string *message = deal_error_message_value(error);
            return message == NULL ? DEAL_STATUS_ABSENT
                                   : deal_host_issue_view(message, out);
        }
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    long position = deal_host_class_field_index(access->declared, field);
    if (position < 0) {
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    deal_check_input checked = {0};
    uint8_t present = 0;
    if (access->read(&(deal_data){DEAL_DATA_CLASS, 0u, 0.0, instance},
            (size_t)position, &checked, &present, access->context) == 0) {
        return DEAL_STATUS_KIND;
    }
    /* An absent optional read projects to undefined (R2). */
    if (present == 0) {
        return deal_host_issue_view(deal_undefined_singleton, out);
    }
    switch (checked.presentation) {
    case DEAL_PRESENT_DATUM:
        return deal_host_datum_carrier(checked.datum, out);
    case DEAL_PRESENT_MISSING:
        return DEAL_STATUS_ABSENT;
    case DEAL_PRESENT_NOTHING:
        return deal_host_publish_box(DEAL_HOST_BOX_NOTHING, out);
    case DEAL_PRESENT_ASYNC_OPERATION:
        return deal_host_publish_box(DEAL_HOST_BOX_ASYNC_OPERATION, out);
    case DEAL_PRESENT_INVALID_UNICODE:
        return deal_host_publish_box(DEAL_HOST_BOX_INVALID_UNICODE, out);
    }
    deal_platform_fault("host: presentation outside the closed set");
    return DEAL_STATUS_KIND;
}

deal_status deal_field_set(deal_value object, const char *field,
                          deal_value value) {
    if (field == NULL) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *instance = NULL;
    deal_status status = deal_host_class_instance(object, &box, &instance);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        for (size_t index = 0; index < box->member_count; index += 1u) {
            if (deal_host_text_equal(box->members[index].key_bytes, field)) {
                box->members[index].value = value;
                return DEAL_OK;
            }
        }
        box->members = deal_host_grow(box->members, &box->member_capacity,
            box->member_count, sizeof(*box->members),
            "host: class carrier field allocation failed");
        deal_host_member *member = &box->members[box->member_count];
        size_t length = strlen(field);
        member->key_bytes = deal_platform_allocate(length + 1u);
        if (member->key_bytes == NULL) {
            deal_platform_fault("host: class carrier field name allocation"
                                " failed");
        }
        memcpy(member->key_bytes, field, length + 1u);
        member->key_length = length;
        member->value = value;
        box->member_count += 1u;
        return DEAL_OK;
    }
    const char *atom = NULL;
    const deal_host_class_access *access =
        deal_host_class_access_of_instance(instance, &atom);
    if (access == NULL) {
        /* The builtin Error instance's two declared fields are strings (C3). */
        deal_error *error = instance;
        deal_host_box *inner = NULL;
        void *referent = NULL;
        status = deal_host_resolve(value, &inner, &referent);
        if (status != DEAL_OK) {
            return status;
        }
        if (inner != NULL || referent == NULL
                || deal_object_layout(referent) != DEAL_LAYOUT_STRING) {
            return DEAL_STATUS_KIND;
        }
        if (deal_host_text_equal(field, "code")) {
            deal_error_set_code_value(error, referent);
            return DEAL_OK;
        }
        if (deal_host_text_equal(field, "message")) {
            deal_error_set_message_value(error, referent);
            return DEAL_OK;
        }
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    long position = deal_host_class_field_index(access->declared, field);
    if (position < 0) {
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (deal_host_is_untyped_array(crossing, value)) {
        return DEAL_STATUS_KIND;
    }
    deal_host_memo memo = {0};
    deal_check_input checked;
    status = deal_host_classify(crossing, value, &memo, &checked);
    if (status != DEAL_OK) {
        deal_host_memo_release(&memo);
        return DEAL_STATUS_KIND;
    }
    deal_descriptor *declared = access->declared->fields[position].descriptor;
    deal_check_context context;
    context.origin = &crossing->origin;
    context.parameter_index = 0;
    if (deal_check_value(declared, checked, deal_host_boundary_of(declared),
            &context).error != NULL) {
        deal_host_memo_release(&memo);
        return DEAL_STATUS_KIND;
    }
    deal_outcome stored = access->store(access->context, instance,
        (size_t)position, &checked, 1);
    deal_host_memo_release(&memo);
    return stored.error == NULL ? DEAL_OK : DEAL_STATUS_KIND;
}

deal_status deal_field_has(deal_value object, const char *field, int *present) {
    if (present == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    if (field == NULL) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *instance = NULL;
    deal_status status = deal_host_class_instance(object, &box, &instance);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        for (size_t index = 0; index < box->member_count; index += 1u) {
            if (deal_host_text_equal(box->members[index].key_bytes, field)) {
                *present = 1;
                return DEAL_OK;
            }
        }
        *present = 0;
        return DEAL_OK;
    }
    const char *atom = NULL;
    const deal_host_class_access *access =
        deal_host_class_access_of_instance(instance, &atom);
    if (access == NULL) {
        static const char *const builtinFields[] = {"code", "message"};
        for (size_t index = 0; index < 2u; index += 1u) {
            if (deal_host_text_equal(field, builtinFields[index])) {
                *present = 1;
                return DEAL_OK;
            }
        }
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    long position = deal_host_class_field_index(access->declared, field);
    if (position < 0) {
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    deal_check_input checked = {0};
    uint8_t fieldPresent = 0;
    if (access->read(&(deal_data){DEAL_DATA_CLASS, 0u, 0.0, instance},
            (size_t)position, &checked, &fieldPresent, access->context) == 0) {
        return DEAL_STATUS_KIND;
    }
    *present = fieldPresent != 0 ? 1 : 0;
    return DEAL_OK;
}

deal_status deal_field_delete(deal_value object, const char *field) {
    if (field == NULL) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_host_box *box = NULL;
    void *instance = NULL;
    deal_status status = deal_host_class_instance(object, &box, &instance);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        for (size_t index = 0; index < box->member_count; index += 1u) {
            if (!deal_host_text_equal(box->members[index].key_bytes, field)) {
                continue;
            }
            deal_platform_release(box->members[index].key_bytes);
            for (size_t rest = index + 1u; rest < box->member_count;
                    rest += 1u) {
                box->members[rest - 1u] = box->members[rest];
            }
            box->member_count -= 1u;
            return DEAL_OK;
        }
        return DEAL_STATUS_ABSENT;
    }
    const char *atom = NULL;
    const deal_host_class_access *access =
        deal_host_class_access_of_instance(instance, &atom);
    if (access == NULL) {
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    long position = deal_host_class_field_index(access->declared, field);
    if (position < 0) {
        return DEAL_STATUS_UNKNOWN_FIELD;
    }
    deal_check_input absent = {0};
    deal_outcome cleared = access->store(access->context, instance,
        (size_t)position, &absent, 0);
    return cleared.error == NULL ? DEAL_OK : DEAL_STATUS_KIND;
}

/* -- function values ---------------------------------------------------- */

deal_status deal_function_signature(deal_value value,
                                   const char **descriptorText) {
    if (descriptorText == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    if (box != NULL) {
        if (box->kind != DEAL_HOST_BOX_FUNCTION) {
            return DEAL_STATUS_KIND;
        }
        *descriptorText = box->signature;
        return DEAL_OK;
    }
    if (deal_host_kind_of_object(referent) != DEAL_KIND_FUNCTION) {
        return DEAL_STATUS_KIND;
    }
    *descriptorText = deal_fn_signature(referent);
    return DEAL_OK;
}

/* =========================================================================
 * Handles (H3)
 * ========================================================================= */

deal_status deal_retain(deal_value value, deal_handle *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_box *box = NULL;
    void *referent = NULL;
    deal_status status = deal_host_resolve(value, &box, &referent);
    if (status != DEAL_OK) {
        return status;
    }
    deal_data retained;
    if (box != NULL) {
        if (box->kind == DEAL_HOST_BOX_MISSING
                || box->kind == DEAL_HOST_BOX_NOTHING
                || box->kind == DEAL_HOST_BOX_CLASS_ATOM
                || box->kind == DEAL_HOST_BOX_ASYNC_OPERATION) {
            return DEAL_STATUS_KIND;
        }
        deal_host_memo memo = {0};
        deal_check_input checked;
        if (deal_host_classify(crossing, value, &memo, &checked) != DEAL_OK
                || checked.presentation != DEAL_PRESENT_DATUM) {
            deal_host_memo_release(&memo);
            return DEAL_STATUS_KIND;
        }
        retained = checked.datum;
        deal_host_memo_release(&memo);
    } else {
        retained = deal_host_datum_of_referent(referent);
    }
    deal_host_handles_ensure();
    deal_host_handles = deal_host_grow(deal_host_handles,
        &deal_host_handle_capacity, deal_host_handle_count,
        sizeof(*deal_host_handles), "host: handle table allocation failed");
    deal_host_handle_counter += 1u;
    if (deal_host_handle_counter == 0u) {
        deal_host_handle_counter = 1u;
    }
    deal_host_handle_entry *entry = &deal_host_handles[deal_host_handle_count];
    entry->handle = deal_host_handle_counter;
    entry->value = retained;
    entry->live = 1;
    deal_host_handle_count += 1u;
    *out = entry->handle;
    return DEAL_OK;
}

deal_status deal_handle_value(deal_handle handle, const char *descriptorText,
                             deal_value *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_handle_entry *entry = deal_host_handle_find(handle);
    if (entry == NULL) {
        return DEAL_STATUS_STALE_HANDLE;
    }
    if (descriptorText != NULL) {
        if (!deal_host_descriptor_is_data(descriptorText)) {
            return DEAL_STATUS_BAD_DESCRIPTOR;
        }
        deal_descriptor *declared = deal_descriptor_intern(descriptorText);
        deal_check_input checked;
        deal_host_present(entry->value, &checked);
        deal_check_context context;
        context.origin = &crossing->origin;
        context.parameter_index = 0;
        if (deal_check_value(declared, checked, deal_host_boundary_of(declared),
                &context).error != NULL) {
            return DEAL_STATUS_KIND;
        }
    }
    return deal_host_datum_carrier(entry->value, out);
}

deal_status deal_release(deal_handle handle) {
    deal_host_handle_entry *entry = deal_host_handle_find(handle);
    if (entry == NULL) {
        return DEAL_STATUS_STALE_HANDLE;
    }
    entry->live = 0;
    entry->value = (deal_data){0u, 0u, 0.0, NULL};
    return DEAL_OK;
}

deal_status deal_error_from_handle(deal_handle handle, deal_host_failure *out) {
    if (out == NULL) {
        deal_platform_fault("host: null out-parameter");
    }
    deal_host_crossing *crossing = deal_host_crossing_current();
    if (crossing == NULL) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_host_handle_entry *entry = deal_host_handle_find(handle);
    if (entry == NULL) {
        return DEAL_STATUS_STALE_HANDLE;
    }
    void *referent = deal_data_referent(&entry->value);
    if (referent == NULL
            || deal_host_kind_of_object(referent) != DEAL_KIND_CLASS
            || deal_object_layout(referent) != DEAL_LAYOUT_ERROR) {
        return DEAL_STATUS_KIND;
    }
    out->kind = DEAL_FAILURE_DEAL_ERROR;
    out->code = NULL;
    out->message = NULL;
    out->expected = NULL;
    out->actual = NULL;
    out->error = deal_host_token_issue(crossing, referent);
    return DEAL_OK;
}

/* =========================================================================
 * The native host load (H1, H7, `LoadHostModule`)
 * ========================================================================= */

/* One rendered host-load declaration arm at the import origin. */
static deal_error *deal_host_load_arm(uint32_t arm_id,
                                      const char *module_path,
                                      const char *name,
                                      const char *expected,
                                      const char *actual,
                                      const char *reason,
                                      const deal_origin *origin) {
    deal_arm_parameter parameters[4];
    size_t count = 0u;
    switch (arm_id) {
    case DEAL_ARM_ID_HOST_LOAD_FAILED:
        parameters[count++] = (deal_arm_parameter){"module", module_path};
        parameters[count++] = (deal_arm_parameter){"reason", reason};
        break;
    case DEAL_ARM_ID_HOST_LOAD_MISSING_EXPORT:
        parameters[count++] = (deal_arm_parameter){"name", name};
        parameters[count++] = (deal_arm_parameter){"module", module_path};
        break;
    case DEAL_ARM_ID_HOST_LOAD_SIGNATURE_MISMATCH:
    case DEAL_ARM_ID_HOST_LOAD_IDENTITY_MISMATCH:
        parameters[count++] = (deal_arm_parameter){"name", name};
        parameters[count++] = (deal_arm_parameter){"module", module_path};
        parameters[count++] = (deal_arm_parameter){"expected", expected};
        parameters[count++] = (deal_arm_parameter){"actual", actual};
        break;
    case DEAL_ARM_ID_HOST_LOAD_INVALID_METADATA:
        parameters[count++] = (deal_arm_parameter){"name", name};
        parameters[count++] = (deal_arm_parameter){"module", module_path};
        parameters[count++] = (deal_arm_parameter){"defaults|fields", reason};
        break;
    default:
        deal_platform_fault("host: load rendered an unexpected arm");
    }
    return deal_error_materialize(arm_id, parameters, count, NULL, NULL, origin,
        NULL);
}

/* The class name of one canonical class atom (its final component). */
static const char *deal_host_class_name(const char *identity,
                                        const char *fallback) {
    if (identity == NULL) {
        return fallback;
    }
    const char *slash = strrchr(identity, '/');
    return slash == NULL ? identity : slash + 1;
}

/* The pinned native reason text of the absent-registration arm (H7). */
static const char deal_host_unregistered_reason[] =
    "no registered native implementation";

/* The defect of one registered class record's own metadata (H2/H7), or NULL. */
static const char *deal_host_class_metadata_defect(
        const deal_host_class *klass) {
    if (klass->identity == NULL || klass->identity[0] == '\0'
            || !deal_host_descriptor_is_class_atom(klass->identity)) {
        return "fields";
    }
    if (klass->field_count != 0u && klass->fields == NULL) {
        return "fields";
    }
    for (size_t index = 0; index < klass->field_count; index += 1u) {
        const deal_host_field *field = &klass->fields[index];
        if (field->name == NULL || field->name[0] == '\0') {
            return "fields";
        }
        if (field->descriptor == NULL
                || !deal_host_descriptor_is_data(field->descriptor)) {
            return "fields";
        }
        if (field->optional != 0 && field->optional != 1) {
            return "fields";
        }
        for (size_t other = 0; other < index; other += 1u) {
            if (deal_host_text_equal(klass->fields[other].name, field->name)) {
                return "fields";
            }
        }
    }
    return NULL;
}

/* The declared export's closed kind and async marker, from its descriptor. */
static deal_host_export_kind deal_host_declared_kind(const char *descriptor,
                                                     int *is_async) {
    if (descriptor != NULL && deal_host_descriptor_is_class_atom(descriptor)) {
        *is_async = 0;
        return DEAL_HOST_EXPORT_CLASS;
    }
    int async = descriptor != NULL && descriptor[0] == 'a' ? 1 : 0;
    *is_async = async;
    return async != 0 ? DEAL_HOST_EXPORT_ASYNC_FUNCTION
                      : DEAL_HOST_EXPORT_FUNCTION;
}

/* The closed C protocol shape of one registered export record (H2's
 * record/linkage rules): a usable name and descriptor, a unique name in the
 * registration, exactly one non-NULL pointer of the record's own kind, every
 * inactive pointer NULL, `is_async` agreeing with the kind, and a class record
 * pointing into the module's declared class array. A violation is the
 * deterministic one-line host protocol failure and exit 2, never an invented
 * DEAL arm and never a silently accepted registration. */
static void deal_host_registration_check(const deal_host_module *registered) {
    for (size_t index = 0; index < registered->export_count; index += 1u) {
        const deal_host_export *entry = &registered->exports[index];
        if (entry->name == NULL || entry->name[0] == '\0'
                || entry->descriptor == NULL
                || entry->descriptor[0] == '\0') {
            deal_host_protocol("a registered export without its name or"
                               " descriptor");
        }
        for (size_t other = 0; other < index; other += 1u) {
            if (deal_host_text_equal(registered->exports[other].name,
                    entry->name)) {
                deal_host_protocol("a registration declares one export name"
                                   " twice");
            }
        }
        switch (entry->kind) {
        case DEAL_HOST_EXPORT_FUNCTION:
            if (entry->is_async != 0 || entry->function == NULL
                    || entry->async_function != NULL
                    || entry->class_value != NULL) {
                deal_host_protocol("a registered function export's markers"
                                   " disagree with its kind");
            }
            break;
        case DEAL_HOST_EXPORT_ASYNC_FUNCTION:
            if (entry->is_async != 1 || entry->async_function == NULL
                    || entry->function != NULL
                    || entry->class_value != NULL) {
                deal_host_protocol("a registered async export's markers"
                                   " disagree with its kind");
            }
            break;
        case DEAL_HOST_EXPORT_CLASS: {
            int declared = 0;
            for (size_t other = 0; other < registered->class_count;
                    other += 1u) {
                if (entry->class_value == &registered->classes[other]) {
                    declared = 1;
                    break;
                }
            }
            if (entry->is_async != 0 || entry->class_value == NULL
                    || entry->function != NULL
                    || entry->async_function != NULL || declared == 0) {
                deal_host_protocol("a registered class export's markers"
                                   " disagree with its kind");
            }
            break;
        }
        default:
            deal_host_protocol("a registered export of an unknown kind");
        }
    }
}

deal_outcome deal_host_load(const deal_host_declared_module *declared,
                            const deal_origin *origin,
                            const deal_host_binding **out) {
    deal_outcome outcome;
    outcome.error = NULL;
    if (declared == NULL || origin == NULL || out == NULL) {
        deal_platform_fault("host: malformed host load call");
    }
    if (declared->specifier == NULL || declared->module_path == NULL) {
        deal_platform_fault("host: a declared module without its identity");
    }
    if ((declared->declared_export_count != 0u
                && declared->declared_exports == NULL)
            || (declared->declared_class_count != 0u
                && declared->declared_classes == NULL)) {
        deal_platform_fault("host: a declared module without its declarations");
    }
    const deal_host_module *registered = NULL;
    for (size_t index = 0; index < deal_host_registry_count; index += 1u) {
        if (deal_host_text_equal(deal_host_registry[index].specifier,
                declared->specifier)) {
            registered = deal_host_registry[index].module;
            break;
        }
    }
    if (registered == NULL) {
        outcome.error = deal_host_load_arm(DEAL_ARM_ID_HOST_LOAD_FAILED,
            declared->specifier, NULL, NULL, NULL,
            deal_host_unregistered_reason, origin);
        return outcome;
    }
    if (registered->specifier == NULL
            || (registered->export_count != 0u && registered->exports == NULL)
            || (registered->class_count != 0u && registered->classes == NULL)) {
        /* The registration record's own shape (H2's record/linkage rules), not
         * declared class metadata: one deterministic protocol line, exit 2,
         * `*out` unwritten and no invented DEAL arm. */
        deal_host_protocol("a registration without its module specifier or"
                           " its declared records");
    }
    /* Record consistency before use (H2): an inconsistent C protocol shape is
     * the deterministic protocol failure, never a DEAL arm. */
    deal_host_registration_check(registered);
    for (size_t index = 0; index < registered->class_count; index += 1u) {
        const char *defect =
            deal_host_class_metadata_defect(&registered->classes[index]);
        if (defect == NULL) {
            continue;
        }
        const char *identity = registered->classes[index].identity;
        outcome.error = deal_host_load_arm(
            DEAL_ARM_ID_HOST_LOAD_INVALID_METADATA, declared->specifier,
            deal_host_class_name(identity, declared->specifier), NULL, NULL,
            defect, origin);
        return outcome;
    }
    if (registered->specifier[0] != '\0'
            && !deal_host_text_equal(registered->specifier,
                declared->specifier)) {
        /* The registry entry pairs one raw specifier with the registration
         * record; a record whose own specifier names another module is the same
         * C protocol shape defect, never a declared class identity mismatch. */
        deal_host_protocol("a registration whose specifier disagrees with its"
                           " registry key");
    }
    const deal_host_export **resolved = NULL;
    if (declared->declared_export_count != 0u) {
        resolved = deal_platform_allocate(deal_platform_checked_multiply(
            declared->declared_export_count, sizeof(*resolved)));
        if (resolved == NULL) {
            deal_platform_fault("host: binding export table allocation"
                                " failed");
        }
    }
    for (size_t index = 0; index < declared->declared_export_count;
            index += 1u) {
        const deal_host_export *declaredExport =
            &declared->declared_exports[index];
        int declaredAsync = 0;
        deal_host_export_kind declaredKind =
            deal_host_declared_kind(declaredExport->descriptor,
                &declaredAsync);
        const deal_host_export *match = NULL;
        for (size_t other = 0; other < registered->export_count; other += 1u) {
            if (deal_host_text_equal(registered->exports[other].name,
                    declaredExport->name)) {
                match = &registered->exports[other];
                break;
            }
        }
        if (match == NULL) {
            deal_platform_release(resolved);
            outcome.error = deal_host_load_arm(
                DEAL_ARM_ID_HOST_LOAD_MISSING_EXPORT, declared->specifier,
                declaredExport->name, NULL, NULL, NULL, origin);
            return outcome;
        }
        if (match->descriptor == NULL || declaredExport->descriptor == NULL) {
            /* A record member without its canonical text is a C protocol shape
             * defect (H2), never malformed declared class metadata. */
            deal_platform_release(resolved);
            deal_host_protocol("a declared or registered export without its"
                               " descriptor");
        }
        if (match->kind != declaredKind || match->is_async != declaredAsync
                || !deal_host_text_equal(match->descriptor,
                    declaredExport->descriptor)
                || (declaredKind == DEAL_HOST_EXPORT_FUNCTION
                    && match->function == NULL)
                || (declaredKind == DEAL_HOST_EXPORT_ASYNC_FUNCTION
                    && match->async_function == NULL)) {
            deal_platform_release(resolved);
            outcome.error = deal_host_load_arm(
                DEAL_ARM_ID_HOST_LOAD_SIGNATURE_MISMATCH,
                declared->specifier, declaredExport->name,
                declaredExport->descriptor, match->descriptor, NULL, origin);
            return outcome;
        }
        if (declaredKind == DEAL_HOST_EXPORT_CLASS
                && (match->class_value == NULL
                    || match->class_value->identity == NULL
                    || !deal_host_text_equal(match->class_value->identity,
                        declaredExport->descriptor))) {
            deal_platform_release(resolved);
            outcome.error = deal_host_load_arm(
                DEAL_ARM_ID_HOST_LOAD_IDENTITY_MISMATCH,
                declared->specifier, declaredExport->name,
                declaredExport->descriptor,
                match->class_value == NULL
                        || match->class_value->identity == NULL
                    ? "" : match->class_value->identity,
                NULL, origin);
            return outcome;
        }
        resolved[index] = match;
    }
    /* Every declared class's identity and declared metadata (H2/H7). */
    for (size_t index = 0; index < declared->declared_class_count;
            index += 1u) {
        const deal_host_class *declaredClass =
            &declared->declared_classes[index];
        const deal_host_class *match = NULL;
        for (size_t other = 0; other < registered->class_count; other += 1u) {
            if (deal_host_text_equal(registered->classes[other].identity,
                    declaredClass->identity)) {
                match = &registered->classes[other];
                break;
            }
        }
        if (match == NULL) {
            deal_platform_release(resolved);
            outcome.error = deal_host_load_arm(
                DEAL_ARM_ID_HOST_LOAD_IDENTITY_MISMATCH, declared->specifier,
                deal_host_class_name(declaredClass->identity,
                    declared->specifier),
                declaredClass->identity, "", NULL, origin);
            return outcome;
        }
        if (match->field_count != declaredClass->field_count) {
            char expected[24];
            char actual[24];
            deal_platform_release(resolved);
            outcome.error = deal_host_load_arm(
                DEAL_ARM_ID_HOST_LOAD_SIGNATURE_MISMATCH,
                declared->specifier,
                deal_host_class_name(declaredClass->identity,
                    declared->specifier),
                deal_host_count_text(declaredClass->field_count, expected,
                    sizeof expected),
                deal_host_count_text(match->field_count, actual,
                    sizeof actual),
                NULL, origin);
            return outcome;
        }
        for (size_t field = 0; field < declaredClass->field_count;
                field += 1u) {
            const deal_host_field *declaredField =
                &declaredClass->fields[field];
            const deal_host_field *matchField = &match->fields[field];
            if (!deal_host_text_equal(declaredField->name, matchField->name)
                    || !deal_host_text_equal(declaredField->descriptor,
                        matchField->descriptor)
                    || declaredField->optional != matchField->optional) {
                deal_platform_release(resolved);
                outcome.error = deal_host_load_arm(
                    DEAL_ARM_ID_HOST_LOAD_SIGNATURE_MISMATCH,
                    declared->specifier, declaredClass->identity,
                    declaredField->descriptor, matchField->descriptor, NULL,
                    origin);
                return outcome;
            }
        }
    }
    deal_host_binding *binding = deal_platform_allocate(sizeof(*binding));
    if (binding == NULL) {
        deal_platform_release(resolved);
        deal_platform_fault("host: binding allocation failed");
    }
    binding->specifier = registered->specifier;
    binding->module = registered;
    binding->exports = resolved;
    binding->export_count = declared->declared_export_count;
    *out = binding;
    return outcome;
}
