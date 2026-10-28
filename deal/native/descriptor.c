/*
 * deal/native/descriptor.c — the `descriptor` service group implementation.
 *
 * The canonical descriptor parser and its interned atoms, the closed
 * actual-kind classification of a checked presentation, the descriptor-driven
 * check of `descriptor.h` (`llvm-native-runtime-value-model` R2/R4) and the
 * class construction validation half of `ConstructClass`.
 *
 * The check composes no failure text: it selects the arm its boundary declares
 * and supplies that arm's projection values, and the `error` group's one
 * renderer (`deal_error_materialize`) instantiates the installed arm's own
 * template. The header declares no error-group prototype — `deal_error` and
 * the outcome family are the opaque C3/C4 handles `deal_rt.h` publishes — and
 * this translation unit reaches representations through `value`, `text`,
 * `container` and `memory` exactly as the group's declared dependencies say.
 */
#include "deal/native/descriptor.h"

#include <string.h>

#include "deal/native/container.h"
#include "deal/native/error.h"
#include "deal/native/platform.h"

/* =========================================================================
 * The interned atoms
 * ========================================================================= */

#define DEAL_DESCRIPTOR_ADMITS_NULL      1u
#define DEAL_DESCRIPTOR_ADMITS_UNDEFINED 2u

struct deal_descriptor {
    char *text;                       /* the canonical text, immutable */
    uint32_t kind;                    /* deal_descriptor_kind */
    uint32_t flags;                   /* the nullish member flags */
    uint32_t reference;               /* the non-zero stable reference */
    uint32_t is_async;                /* a FUNCTION atom's async marker */
    deal_descriptor *inner;           /* ARRAY element / NULLISH base */
    deal_descriptor *result;          /* FUNCTION result */
    deal_descriptor **parameters;     /* FUNCTION parameter list */
    size_t parameter_count;
};

static deal_descriptor **deal_descriptor_atoms;
static size_t deal_descriptor_atom_count;
static size_t deal_descriptor_atom_capacity;

static const char *const deal_descriptor_null_text = "null";
static const char *const deal_descriptor_undefined_text = "undefined";
static const char *const deal_descriptor_boolean_text = "boolean";
static const char *const deal_descriptor_number_text = "number";
static const char *const deal_descriptor_string_text = "string";
static const char *const deal_descriptor_record_text = "table";
static const char *const deal_descriptor_unknown_text = "unknown";
static const char *const deal_descriptor_class_text = "class instance";
static const char *const deal_descriptor_class_token = "class";
static const char *const deal_descriptor_array_text = "array";
static const char *const deal_descriptor_function_text = "function";

static void *deal_descriptor_grow(void *storage, size_t *capacity, size_t needed,
                                  size_t element_size) {
    if (needed < *capacity) {
        return storage;
    }
    size_t grown_capacity = *capacity == 0u ? 8u : *capacity * 2u;
    while (grown_capacity <= needed) {
        grown_capacity *= 2u;
    }
    void *grown = deal_platform_allocate(grown_capacity * element_size);
    if (storage != NULL && needed != 0u) {
        memcpy(grown, storage, needed * element_size);
        deal_platform_release(storage);
    }
    *capacity = grown_capacity;
    return grown;
}

/* One atom's text slice, copied into platform storage. */
static char *deal_descriptor_slice(const char *text, size_t start, size_t end) {
    size_t length = end - start;
    char *copy = deal_platform_allocate(length + 1u);
    if (length != 0u) {
        memcpy(copy, text + start, length);
    }
    copy[length] = 0;
    return copy;
}

static int deal_descriptor_same_bytes(const char *text, size_t start,
                                      const char *expected, size_t length) {
    return strncmp(text + start, expected, length) == 0;
}

/*
 * Interns one atom by its canonical text. A text already interned keeps its
 * first declaration; the caller's shape is checked against it, so one text can
 * never carry two different descriptors.
 */
static deal_descriptor *deal_descriptor_intern_atom(deal_descriptor *shape) {
    for (size_t index = 0; index < deal_descriptor_atom_count; index += 1u) {
        deal_descriptor *existing = deal_descriptor_atoms[index];
        if (strcmp(existing->text, shape->text) != 0) {
            continue;
        }
        if (existing->kind != shape->kind
                || existing->flags != shape->flags
                || existing->is_async != shape->is_async
                || existing->inner != shape->inner
                || existing->result != shape->result
                || existing->parameter_count != shape->parameter_count) {
            deal_platform_fault("descriptor: one text with two shapes");
        }
        deal_platform_release(shape->text);
        if (shape->parameters != NULL) {
            deal_platform_release(shape->parameters);
        }
        deal_platform_release(shape);
        return existing;
    }
    deal_descriptor_atoms = deal_descriptor_grow(deal_descriptor_atoms,
        &deal_descriptor_atom_capacity, deal_descriptor_atom_count,
        sizeof(*deal_descriptor_atoms));
    shape->reference = (uint32_t)(deal_descriptor_atom_count + 1u);
    deal_descriptor_atoms[deal_descriptor_atom_count] = shape;
    deal_descriptor_atom_count += 1u;
    return shape;
}

static deal_descriptor *deal_descriptor_make(const char *text, size_t start,
                                             size_t end, uint32_t kind,
                                             uint32_t flags) {
    deal_descriptor *atom = deal_platform_allocate(sizeof(*atom));
    atom->text = deal_descriptor_slice(text, start, end);
    atom->kind = kind;
    atom->flags = flags;
    atom->reference = 0u;
    atom->is_async = 0u;
    atom->inner = NULL;
    atom->result = NULL;
    atom->parameters = NULL;
    atom->parameter_count = 0u;
    return atom;
}

/* -- the strict parser --------------------------------------------------- */

/* The canonical primitive keywords. */
typedef struct {
    const char *text;
    uint32_t kind;
} deal_descriptor_keyword;

static const deal_descriptor_keyword deal_descriptor_keywords[] = {
    {"null", DEAL_DESCRIPTOR_NULL},
    {"undefined", DEAL_DESCRIPTOR_UNDEFINED},
    {"void", DEAL_DESCRIPTOR_VOID},
    {"boolean", DEAL_DESCRIPTOR_BOOLEAN},
    {"number", DEAL_DESCRIPTOR_NUMBER},
    {"string", DEAL_DESCRIPTOR_STRING},
    {"record", DEAL_DESCRIPTOR_RECORD},
    {"unknown", DEAL_DESCRIPTOR_UNKNOWN}
};

/* 1 when one scalar terminates a descriptor atom in its nesting position. */
static int deal_descriptor_terminator(const char *text, size_t length,
                                      size_t position) {
    return position == length || text[position] == ')' || text[position] == ']'
        || text[position] == ',';
}

static size_t deal_descriptor_parse_at(const char *text, size_t length,
                                       size_t position, int allow_void,
                                       deal_descriptor **out);

static size_t deal_descriptor_expect(const char *text, size_t length,
                                     size_t position, char expected) {
    if (position >= length || text[position] != expected) {
        deal_platform_fault("descriptor: unexpected character");
    }
    return position + 1u;
}

/* One canonical class atom `@modulePath/ClassName` (or the builtin `Error`). */
static deal_descriptor *deal_descriptor_parse_class(const char *text,
                                                    size_t length,
                                                    size_t position,
                                                    size_t *end) {
    size_t segment_start = position + 1u;
    size_t cursor = segment_start;
    while (cursor < length && text[cursor] != ')' && text[cursor] != ']'
            && text[cursor] != ',') {
        cursor += 1u;
    }
    size_t last_slash = length;
    for (size_t index = segment_start; index < cursor; index += 1u) {
        unsigned char byte = (unsigned char)text[index];
        if (byte < 0x20u || byte == 0x7Fu) {
            deal_platform_fault("descriptor: control byte in a class atom");
        }
        if (text[index] == '/') {
            last_slash = index;
        }
    }
    if (last_slash == length || last_slash == segment_start
            || last_slash + 1u == cursor) {
        deal_platform_fault("descriptor: class atom without modulePath/ClassName");
    }
    size_t module_length = last_slash - segment_start;
    /* The builtin Error class is the bare `Error` atom: no class atom carries
     * the reserved `$builtin` namespace and the module path is never empty. */
    if ((module_length == 8u
            && deal_descriptor_same_bytes(text, segment_start, "$builtin", 8u))
            || (module_length > 8u
                && deal_descriptor_same_bytes(text, segment_start, "$builtin/", 9u))) {
        deal_platform_fault("descriptor: class atom with a reserved module path");
    }
    *end = cursor;
    return deal_descriptor_intern_atom(deal_descriptor_make(text, position, cursor,
        DEAL_DESCRIPTOR_CLASS, 0u));
}

/* One function descriptor `(` params `)->` result. `start` is where the atom's
 * canonical text begins (the `async` marker included) and `position` where the
 * parameter list begins. */
static deal_descriptor *deal_descriptor_parse_function(const char *text,
                                                       size_t length,
                                                       size_t start,
                                                       size_t position,
                                                       int is_async,
                                                       size_t *end) {
    size_t cursor = position;
    deal_descriptor **parameters = NULL;
    size_t parameter_count = 0u;
    size_t parameter_capacity = 0u;
    cursor = deal_descriptor_expect(text, length, cursor, '(');
    if (cursor < length && text[cursor] != ')') {
        for (;;) {
            deal_descriptor *parameter = NULL;
            cursor = deal_descriptor_parse_at(text, length, cursor, 0, &parameter);
            parameters = deal_descriptor_grow(parameters, &parameter_capacity,
                parameter_count, sizeof(*parameters));
            parameters[parameter_count] = parameter;
            parameter_count += 1u;
            if (cursor < length && text[cursor] == ',') {
                cursor += 1u;
                continue;
            }
            break;
        }
    }
    cursor = deal_descriptor_expect(text, length, cursor, ')');
    cursor = deal_descriptor_expect(text, length, cursor, '-');
    cursor = deal_descriptor_expect(text, length, cursor, '>');
    deal_descriptor *result = NULL;
    cursor = deal_descriptor_parse_at(text, length, cursor, 1, &result);
    deal_descriptor *atom = deal_descriptor_make(text, start, cursor,
        DEAL_DESCRIPTOR_FUNCTION, 0u);
    atom->is_async = is_async != 0 ? 1u : 0u;
    atom->parameters = parameters;
    atom->parameter_count = parameter_count;
    atom->result = result;
    *end = cursor;
    return deal_descriptor_intern_atom(atom);
}

/* One nullish descriptor `?T`, `!T`, `?!T` over a non-nullish base. */
static deal_descriptor *deal_descriptor_parse_nullish(const char *text,
                                                      size_t length,
                                                      size_t position,
                                                      size_t *end) {
    uint32_t flags = 0u;
    size_t cursor = position;
    if (text[cursor] == '?') {
        flags |= DEAL_DESCRIPTOR_ADMITS_NULL;
        cursor += 1u;
    }
    if (cursor < length && text[cursor] == '!') {
        flags |= DEAL_DESCRIPTOR_ADMITS_UNDEFINED;
        cursor += 1u;
    }
    deal_descriptor *base = NULL;
    cursor = deal_descriptor_parse_at(text, length, cursor, 0, &base);
    if (base->kind == DEAL_DESCRIPTOR_NULLISH
            || base->kind == DEAL_DESCRIPTOR_UNKNOWN
            || base->kind == DEAL_DESCRIPTOR_VOID
            || flags == 0u
            || (base->kind == DEAL_DESCRIPTOR_NULL
                && (flags & DEAL_DESCRIPTOR_ADMITS_NULL) != 0u)
            || (base->kind == DEAL_DESCRIPTOR_UNDEFINED
                && (flags & DEAL_DESCRIPTOR_ADMITS_UNDEFINED) != 0u)) {
        deal_platform_fault("descriptor: invalid nullish descriptor");
    }
    deal_descriptor *atom = deal_descriptor_make(text, position, cursor,
        DEAL_DESCRIPTOR_NULLISH, flags);
    atom->inner = base;
    *end = cursor;
    return deal_descriptor_intern_atom(atom);
}

static size_t deal_descriptor_parse_at(const char *text, size_t length,
                                       size_t position, int allow_void,
                                       deal_descriptor **out) {
    if (position >= length) {
        deal_platform_fault("descriptor: unexpected end of text");
    }
    char lead = text[position];
    if (lead == '[') {
        deal_descriptor *element = NULL;
        size_t cursor = deal_descriptor_parse_at(text, length, position + 1u, 0,
            &element);
        cursor = deal_descriptor_expect(text, length, cursor, ']');
        deal_descriptor *atom = deal_descriptor_make(text, position, cursor,
            DEAL_DESCRIPTOR_ARRAY, 0u);
        atom->inner = element;
        *out = deal_descriptor_intern_atom(atom);
        return cursor;
    }
    if (length - position >= 14u
            && deal_descriptor_same_bytes(text, position, "null|undefined", 14u)) {
        deal_descriptor *undefined_atom = NULL;
        size_t cursor = deal_descriptor_parse_at(text, length, position + 5u, 0,
            &undefined_atom);
        (void)cursor;
        cursor = position + 14u;
        deal_descriptor *atom = deal_descriptor_make(text, position, cursor,
            DEAL_DESCRIPTOR_NULLISH, DEAL_DESCRIPTOR_ADMITS_NULL);
        atom->inner = undefined_atom;
        *out = deal_descriptor_intern_atom(atom);
        return cursor;
    }
    if (lead == '?' || lead == '!') {
        size_t cursor = position;
        *out = deal_descriptor_parse_nullish(text, length, position, &cursor);
        return cursor;
    }
    if (lead == '@') {
        size_t cursor = position;
        *out = deal_descriptor_parse_class(text, length, position, &cursor);
        return cursor;
    }
    if (length - position >= 6u
            && deal_descriptor_same_bytes(text, position, "async(", 6u)) {
        size_t cursor = position;
        *out = deal_descriptor_parse_function(text, length, position,
            position + 5u, 1, &cursor);
        return cursor;
    }
    if (lead == '(') {
        size_t cursor = position;
        *out = deal_descriptor_parse_function(text, length, position, position, 0,
            &cursor);
        return cursor;
    }
    for (size_t index = 0;
            index < sizeof(deal_descriptor_keywords)
                / sizeof(deal_descriptor_keywords[0]); index += 1u) {
        const deal_descriptor_keyword *keyword = &deal_descriptor_keywords[index];
        size_t keyword_length = strlen(keyword->text);
        if (length - position < keyword_length
                || !deal_descriptor_same_bytes(text, position, keyword->text,
                    keyword_length)) {
            continue;
        }
        size_t cursor = position + keyword_length;
        if (!deal_descriptor_terminator(text, length, cursor)) {
            continue;
        }
        if (keyword->kind == DEAL_DESCRIPTOR_VOID && !allow_void) {
            deal_platform_fault("descriptor: void outside a function result");
        }
        *out = deal_descriptor_intern_atom(deal_descriptor_make(text, position,
            cursor, keyword->kind, 0u));
        return cursor;
    }
    /* The builtin Error class: the one class atom without the leading '@'. */
    if (length - position >= 5u
            && deal_descriptor_same_bytes(text, position, "Error", 5u)
            && deal_descriptor_terminator(text, length, position + 5u)) {
        *out = deal_descriptor_intern_atom(deal_descriptor_make(text, position,
            position + 5u, DEAL_DESCRIPTOR_CLASS, 0u));
        return position + 5u;
    }
    deal_platform_fault("descriptor: not a canonical descriptor text");
    return position;
}

deal_descriptor *deal_descriptor_intern(const char *descriptor_text) {
    if (descriptor_text == NULL) {
        deal_platform_fault("descriptor: null descriptor text");
    }
    size_t length = strlen(descriptor_text);
    deal_descriptor *atom = NULL;
    size_t end = deal_descriptor_parse_at(descriptor_text, length, 0u, 0, &atom);
    if (end != length) {
        deal_platform_fault("descriptor: trailing content after the descriptor");
    }
    return atom;
}

const char *deal_descriptor_text(deal_descriptor *atom) {
    if (atom == NULL) {
        deal_platform_fault("descriptor: null atom");
    }
    return atom->text;
}

deal_descriptor_kind deal_descriptor_type(deal_descriptor *atom) {
    if (atom == NULL) {
        deal_platform_fault("descriptor: null atom");
    }
    return (deal_descriptor_kind)atom->kind;
}

deal_descriptor *deal_descriptor_element(deal_descriptor *atom) {
    if (atom == NULL || atom->kind != DEAL_DESCRIPTOR_ARRAY) {
        deal_platform_fault("descriptor: not an array descriptor");
    }
    return atom->inner;
}

deal_descriptor *deal_descriptor_inner(deal_descriptor *atom) {
    if (atom == NULL || atom->kind != DEAL_DESCRIPTOR_NULLISH) {
        deal_platform_fault("descriptor: not a nullish descriptor");
    }
    return atom->inner;
}

int deal_descriptor_admits_null(deal_descriptor *atom) {
    if (atom == NULL || atom->kind != DEAL_DESCRIPTOR_NULLISH) {
        deal_platform_fault("descriptor: not a nullish descriptor");
    }
    return (atom->flags & DEAL_DESCRIPTOR_ADMITS_NULL) != 0u ? 1 : 0;
}

int deal_descriptor_admits_undefined(deal_descriptor *atom) {
    if (atom == NULL || atom->kind != DEAL_DESCRIPTOR_NULLISH) {
        deal_platform_fault("descriptor: not a nullish descriptor");
    }
    return (atom->flags & DEAL_DESCRIPTOR_ADMITS_UNDEFINED) != 0u ? 1 : 0;
}

const char *deal_descriptor_class_atom(deal_descriptor *atom) {
    if (atom == NULL || atom->kind != DEAL_DESCRIPTOR_CLASS) {
        deal_platform_fault("descriptor: not a class descriptor");
    }
    return atom->text;
}

const char *deal_descriptor_kind_text(deal_descriptor *atom) {
    if (atom == NULL) {
        deal_platform_fault("descriptor: null atom");
    }
    switch ((deal_descriptor_kind)atom->kind) {
    case DEAL_DESCRIPTOR_NULL: return deal_descriptor_null_text;
    case DEAL_DESCRIPTOR_UNDEFINED: return deal_descriptor_undefined_text;
    case DEAL_DESCRIPTOR_VOID: return deal_descriptor_undefined_text;
    case DEAL_DESCRIPTOR_BOOLEAN: return deal_descriptor_boolean_text;
    case DEAL_DESCRIPTOR_NUMBER: return deal_descriptor_number_text;
    case DEAL_DESCRIPTOR_STRING: return deal_descriptor_string_text;
    case DEAL_DESCRIPTOR_RECORD: return deal_descriptor_record_text;
    case DEAL_DESCRIPTOR_UNKNOWN: return deal_descriptor_unknown_text;
    case DEAL_DESCRIPTOR_CLASS: return deal_descriptor_class_text;
    case DEAL_DESCRIPTOR_ARRAY: return deal_descriptor_array_text;
    case DEAL_DESCRIPTOR_FUNCTION: return deal_descriptor_function_text;
    case DEAL_DESCRIPTOR_NULLISH:
        return deal_descriptor_kind_text(atom->inner);
    }
    deal_platform_fault("descriptor: kind outside the closed set");
}

const char *deal_descriptor_kind_token(deal_descriptor *atom) {
    if (atom == NULL) {
        deal_platform_fault("descriptor: null atom");
    }
    if (atom->kind == DEAL_DESCRIPTOR_NULLISH) {
        return deal_descriptor_kind_token(atom->inner);
    }
    if (atom->kind == DEAL_DESCRIPTOR_CLASS) {
        return deal_descriptor_class_token;
    }
    return deal_descriptor_kind_text(atom);
}

uint32_t deal_descriptor_reference(deal_descriptor *atom) {
    if (atom == NULL) {
        deal_platform_fault("descriptor: null atom");
    }
    return atom->reference;
}

deal_descriptor *deal_descriptor_of_reference(uint32_t reference) {
    if (reference == 0u) {
        deal_platform_fault("descriptor: absent atom reference");
    }
    if (reference > deal_descriptor_atom_count) {
        deal_platform_fault("descriptor: unknown atom reference");
    }
    return deal_descriptor_atoms[reference - 1u];
}

/* =========================================================================
 * The closed actual-kind classification of one presentation
 * ========================================================================= */

/* One classified presentation: the closed actual kind, the carried canonical
 * atom or signature the emitted module owns, and the closed encoding defect. */
typedef struct {
    uint32_t kind;             /* deal_value_kind */
    const char *class_atom;    /* the carried canonical class atom of a CLASS */
    const char *signature;     /* the carried signature of a FUNCTION */
    uint32_t unicode_defect;   /* deal_unicode_defect of an INVALID_UNICODE */
    void *referent;            /* the managed payload of a managed kind */
} deal_classification;

static const char *const deal_descriptor_error_atom = "Error";

static deal_classification deal_check_classify(const deal_check_input *input) {
    if (input == NULL) {
        deal_platform_fault("check: null check input");
    }
    deal_classification classification;
    classification.class_atom = NULL;
    classification.signature = NULL;
    classification.unicode_defect = DEAL_UNICODE_MALFORMED;
    classification.referent = NULL;
    switch ((deal_presentation)input->presentation) {
    case DEAL_PRESENT_MISSING:
        classification.kind = DEAL_KIND_MISSING;
        return classification;
    case DEAL_PRESENT_NOTHING:
        classification.kind = DEAL_KIND_NOTHING;
        return classification;
    case DEAL_PRESENT_ASYNC_OPERATION:
        classification.kind = DEAL_KIND_ASYNC_OPERATION;
        return classification;
    case DEAL_PRESENT_INVALID_UNICODE:
        if (input->unicode_defect != DEAL_UNICODE_MALFORMED
                && input->unicode_defect != DEAL_UNICODE_SURROGATE) {
            deal_platform_fault("check: unknown Unicode defect");
        }
        classification.kind = DEAL_KIND_INVALID_UNICODE;
        classification.unicode_defect = input->unicode_defect;
        return classification;
    case DEAL_PRESENT_DATUM:
        break;
    default:
        deal_platform_fault("check: presentation outside the closed set");
    }
    classification.referent = input->datum.reference;
    switch ((deal_data_tag)input->datum.tag) {
    case DEAL_DATA_UNDEFINED:
        classification.kind = DEAL_KIND_UNDEFINED;
        return classification;
    case DEAL_DATA_NULL:
        classification.kind = DEAL_KIND_NULL;
        return classification;
    case DEAL_DATA_BOOLEAN:
        classification.kind = DEAL_KIND_BOOLEAN;
        return classification;
    case DEAL_DATA_NUMBER:
        classification.kind = DEAL_KIND_NUMBER;
        return classification;
    case DEAL_DATA_STRING:
        classification.kind = DEAL_KIND_STRING;
        return classification;
    case DEAL_DATA_ARRAY:
        classification.kind = DEAL_KIND_ARRAY;
        return classification;
    case DEAL_DATA_TABLE:
        classification.kind = DEAL_KIND_TABLE;
        return classification;
    case DEAL_DATA_FUNCTION:
        if (input->signature == NULL) {
            deal_platform_fault("check: function datum without its signature");
        }
        classification.kind = DEAL_KIND_FUNCTION;
        classification.signature = input->signature;
        return classification;
    case DEAL_DATA_CLASS:
        if (input->class_atom == NULL) {
            deal_platform_fault("check: class datum without its class atom");
        }
        classification.kind = DEAL_KIND_CLASS;
        classification.class_atom = input->class_atom;
        return classification;
    case DEAL_DATA_ERROR:
        /* The builtin Error instance is the one nominal class whose canonical
         * atom carries no module path (R2, C3). */
        classification.kind = DEAL_KIND_CLASS;
        classification.class_atom = deal_descriptor_error_atom;
        return classification;
    }
    deal_platform_fault("check: datum tag outside the closed set");
}

/* The typed-boundary token of one classified presentation (P2 item 1). */
static const char *deal_check_typed_token(const deal_classification *classification) {
    switch (classification->kind) {
    case DEAL_KIND_MISSING:
    case DEAL_KIND_UNDEFINED:
        return "undefined";
    case DEAL_KIND_NULL:
        return "null";
    case DEAL_KIND_BOOLEAN:
        return "boolean";
    case DEAL_KIND_NUMBER:
        return "number";
    case DEAL_KIND_STRING:
        return "string";
    case DEAL_KIND_INVALID_UNICODE:
        return "invalid-unicode";
    case DEAL_KIND_TABLE:
        return "table";
    case DEAL_KIND_ARRAY:
        return "array";
    case DEAL_KIND_FUNCTION:
        return "function";
    case DEAL_KIND_ASYNC_OPERATION:
        return "async-operation";
    case DEAL_KIND_NOTHING:
        return "nothing";
    case DEAL_KIND_CLASS:
        return classification->class_atom;
    }
    deal_platform_fault("check: actual kind outside the closed set");
}

/* The shared host-carrier projection of one classified presentation (P2 item 2). */
static const char *deal_check_carrier_token(const deal_classification *classification) {
    switch (classification->kind) {
    case DEAL_KIND_MISSING:
        return "nil";
    case DEAL_KIND_UNDEFINED:
        return "undefined";
    case DEAL_KIND_BOOLEAN:
        return "boolean";
    case DEAL_KIND_NUMBER:
        return "number";
    case DEAL_KIND_STRING:
    case DEAL_KIND_INVALID_UNICODE:
        return "string";
    default:
        return "table";
    }
}

/* =========================================================================
 * The descriptor-kind core (D3)
 * ========================================================================= */

typedef enum {
    DEAL_CHECK_PASS = 0,
    DEAL_CHECK_KIND_MISMATCH,
    DEAL_CHECK_INVALID_UNICODE,
    DEAL_CHECK_CLASS_IDENTITY,
    DEAL_CHECK_SIGNATURE_MISMATCH
} deal_check_case;

typedef struct {
    uint32_t case_kind;               /* deal_check_case */
    deal_descriptor *cell_descriptor; /* the boundary's own declared descriptor */
    deal_descriptor *descriptor;      /* the failing position's descriptor */
    const char *actual;               /* the carried atom/signature of an identity case */
} deal_check_failure;

/* 1 when an array's recorded element descriptor is compatible with `element`
 * (R4: the persistent descriptor decides, never the present elements). An
 * array that records no element descriptor is compatible with every one. */
static int deal_check_element_compatible(deal_descriptor *element,
                                          const void *referent) {
    uint32_t recorded = deal_container_array_element_descriptor(
        (const deal_array *)referent);
    if (recorded == 0u) {
        return 1;
    }
    return recorded == deal_descriptor_reference(element);
}

static deal_check_case deal_check_core(deal_descriptor *descriptor,
                                       const deal_classification *value,
                                       deal_check_failure *failure) {
    deal_classification undefined_value = *value;
    if (value->kind == DEAL_KIND_MISSING) {
        /* An absent read is `undefined` before any comparison or check (R2). */
        undefined_value.kind = DEAL_KIND_UNDEFINED;
        undefined_value.referent = NULL;
        value = &undefined_value;
    }
    failure->descriptor = descriptor;
    failure->actual = deal_check_typed_token(value);
    switch ((deal_descriptor_kind)descriptor->kind) {
    case DEAL_DESCRIPTOR_UNDEFINED:
    case DEAL_DESCRIPTOR_VOID:
        return value->kind == DEAL_KIND_UNDEFINED
            ? DEAL_CHECK_PASS : DEAL_CHECK_KIND_MISMATCH;
    case DEAL_DESCRIPTOR_NULL:
        return value->kind == DEAL_KIND_NULL
            ? DEAL_CHECK_PASS : DEAL_CHECK_KIND_MISMATCH;
    case DEAL_DESCRIPTOR_BOOLEAN:
        return value->kind == DEAL_KIND_BOOLEAN
            ? DEAL_CHECK_PASS : DEAL_CHECK_KIND_MISMATCH;
    case DEAL_DESCRIPTOR_NUMBER:
        return value->kind == DEAL_KIND_NUMBER
            ? DEAL_CHECK_PASS : DEAL_CHECK_KIND_MISMATCH;
    case DEAL_DESCRIPTOR_STRING:
        if (value->kind == DEAL_KIND_STRING) {
            return DEAL_CHECK_PASS;
        }
        return value->kind == DEAL_KIND_INVALID_UNICODE
            ? DEAL_CHECK_INVALID_UNICODE : DEAL_CHECK_KIND_MISMATCH;
    case DEAL_DESCRIPTOR_RECORD:
        return value->kind == DEAL_KIND_TABLE
            ? DEAL_CHECK_PASS : DEAL_CHECK_KIND_MISMATCH;
    case DEAL_DESCRIPTOR_UNKNOWN:
        switch (value->kind) {
        case DEAL_KIND_NULL:
        case DEAL_KIND_UNDEFINED:
        case DEAL_KIND_BOOLEAN:
        case DEAL_KIND_NUMBER:
        case DEAL_KIND_STRING:
        case DEAL_KIND_TABLE:
        case DEAL_KIND_ARRAY:
        case DEAL_KIND_CLASS:
        case DEAL_KIND_FUNCTION:
            return DEAL_CHECK_PASS;
        default:
            return DEAL_CHECK_KIND_MISMATCH;
        }
    case DEAL_DESCRIPTOR_CLASS:
        if (value->kind != DEAL_KIND_CLASS) {
            return DEAL_CHECK_KIND_MISMATCH;
        }
        if (strcmp(descriptor->text, value->class_atom) == 0) {
            return DEAL_CHECK_PASS;
        }
        failure->actual = value->class_atom;
        return DEAL_CHECK_CLASS_IDENTITY;
    case DEAL_DESCRIPTOR_ARRAY:
        if (value->kind != DEAL_KIND_ARRAY) {
            return DEAL_CHECK_KIND_MISMATCH;
        }
        return deal_check_element_compatible(descriptor->inner, value->referent)
            ? DEAL_CHECK_PASS : DEAL_CHECK_KIND_MISMATCH;
    case DEAL_DESCRIPTOR_NULLISH:
        if ((value->kind == DEAL_KIND_NULL
                && deal_descriptor_admits_null(descriptor))
                || (value->kind == DEAL_KIND_UNDEFINED
                    && deal_descriptor_admits_undefined(descriptor))) {
            return DEAL_CHECK_PASS;
        }
        return deal_check_core(descriptor->inner, value, failure);
    case DEAL_DESCRIPTOR_FUNCTION:
        if (value->kind == DEAL_KIND_FUNCTION) {
            if (strcmp(descriptor->text, value->signature) == 0) {
                return DEAL_CHECK_PASS;
            }
            failure->actual = value->signature;
            return DEAL_CHECK_SIGNATURE_MISMATCH;
        }
        return DEAL_CHECK_KIND_MISMATCH;
    }
    deal_platform_fault("check: descriptor kind outside the closed set");
}

/* =========================================================================
 * The closed arm selection of one check boundary
 * ========================================================================= */

/* The arm one core failure renders at one descriptor-kind projection set. */
static uint32_t deal_check_arm_of(uint32_t case_kind) {
    switch ((deal_check_case)case_kind) {
    case DEAL_CHECK_KIND_MISMATCH:
        return DEAL_ARM_ID_TYPED_BOUNDARY_KIND;
    case DEAL_CHECK_INVALID_UNICODE:
        return DEAL_ARM_ID_TYPED_BOUNDARY_INVALID_UNICODE;
    case DEAL_CHECK_CLASS_IDENTITY:
        return DEAL_ARM_ID_CLASS_IDENTITY;
    case DEAL_CHECK_SIGNATURE_MISMATCH:
        return DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH;
    case DEAL_CHECK_PASS:
        break;
    }
    deal_platform_fault("check: no arm for a passing check");
}

/* The arm one core failure renders at the boundary's declared projection set. */
static uint32_t deal_check_boundary_arm_of(uint32_t boundary_kind,
                                           uint32_t case_kind) {
    switch (boundary_kind) {
    case DEAL_BOUNDARY_TYPE_DESCRIPTOR:
    case DEAL_BOUNDARY_FUNCTION_SIGNATURE:
        return deal_check_arm_of(case_kind);
    case DEAL_BOUNDARY_ASYNC_COMPLETION:
        switch ((deal_check_case)case_kind) {
        case DEAL_CHECK_KIND_MISMATCH:
        case DEAL_CHECK_INVALID_UNICODE:
            return DEAL_ARM_ID_ASYNC_COMPLETION_KIND;
        case DEAL_CHECK_CLASS_IDENTITY:
            return DEAL_ARM_ID_CLASS_IDENTITY;
        case DEAL_CHECK_SIGNATURE_MISMATCH:
            return DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH;
        case DEAL_CHECK_PASS:
            break;
        }
        break;
    default:
        break;
    }
    deal_platform_fault("check: boundary kind outside the closed set");
}

/* One arm parameter (its declared name and its projected value). */
static deal_arm_parameter deal_check_parameter(const char *name,
                                              const char *value) {
    deal_arm_parameter parameter;
    parameter.name = name;
    parameter.value = value;
    return parameter;
}

/* The canonical decimal spelling of one one-based index. */
static const char *deal_check_index_text(int32_t index, char *buffer,
                                        size_t capacity) {
    if (index < 1) {
        deal_platform_fault("check: a one-based index must be positive");
    }
    char digits[16];
    size_t length = 0u;
    int32_t magnitude = index;
    while (magnitude > 0) {
        digits[length] = (char)('0' + (magnitude % 10));
        length += 1u;
        magnitude /= 10;
    }
    if (length + 1u > capacity) {
        deal_platform_fault("check: index buffer too small");
    }
    for (size_t position = 0; position < length; position += 1u) {
        buffer[position] = digits[length - 1u - position];
    }
    buffer[length] = 0;
    return buffer;
}

/* The declared named parameters of one tuple the check renders. */
static size_t deal_check_parameters(uint32_t arm_id, const deal_check_failure *failure,
                                    deal_arm_parameter *parameters) {
    switch (arm_id) {
    case DEAL_ARM_ID_TYPED_BOUNDARY_KIND:
        parameters[0] = deal_check_parameter("kind",
            deal_descriptor_kind_text(failure->descriptor));
        return 1u;
    case DEAL_ARM_ID_ASYNC_COMPLETION_KIND:
        parameters[0] = deal_check_parameter("expected",
            deal_descriptor_kind_text(failure->descriptor));
        return 1u;
    case DEAL_ARM_ID_TYPED_BOUNDARY_INVALID_UNICODE:
        return 0u;
    case DEAL_ARM_ID_CLASS_IDENTITY:
        parameters[0] = deal_check_parameter("expected",
            deal_descriptor_class_atom(failure->descriptor));
        parameters[1] = deal_check_parameter("actual", failure->actual);
        return 2u;
    case DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH:
        parameters[0] = deal_check_parameter("expected",
            deal_descriptor_text(failure->descriptor));
        parameters[1] = deal_check_parameter("actual", failure->actual);
        return 2u;
    default:
        deal_platform_fault("check: the check renders an unexpected arm");
    }
}

/* The declared expected field of one tuple the check renders. */
static const char *deal_check_expected_of(uint32_t arm_id,
                                          const deal_check_failure *failure) {
    switch (arm_id) {
    case DEAL_ARM_ID_TYPED_BOUNDARY_KIND:
    case DEAL_ARM_ID_ASYNC_COMPLETION_KIND:
        return deal_descriptor_kind_token(failure->descriptor);
    case DEAL_ARM_ID_TYPED_BOUNDARY_INVALID_UNICODE:
        return deal_descriptor_kind_text(failure->descriptor);
    case DEAL_ARM_ID_CLASS_IDENTITY:
        return deal_descriptor_class_atom(failure->descriptor);
    case DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH:
        return deal_descriptor_text(failure->descriptor);
    case DEAL_ARM_ID_HOST_PARAMETER_CELL:
    case DEAL_ARM_ID_HOST_SYNC_RETURN_NOTHING:
    case DEAL_ARM_ID_HOST_SYNC_RETURN_CELL:
        /* The host cell arms declare CELL_DESCRIPTOR: the declared cell text of
         * the crossing, never the recursed base descriptor (R4/R5). */
        return deal_descriptor_text(failure->cell_descriptor);
    default:
        deal_platform_fault("check: the check renders an unexpected arm");
    }
}

/* The declared actual field of one tuple the check renders. */
static const char *deal_check_actual_of(uint32_t arm_id,
                                        const deal_check_failure *failure,
                                        const deal_classification *value) {
    switch (arm_id) {
    case DEAL_ARM_ID_TYPED_BOUNDARY_KIND:
    case DEAL_ARM_ID_ASYNC_COMPLETION_KIND:
    case DEAL_ARM_ID_CLASS_IDENTITY:
    case DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH:
        return failure->actual;
    case DEAL_ARM_ID_TYPED_BOUNDARY_INVALID_UNICODE:
        return "invalid-unicode";
    case DEAL_ARM_ID_HOST_PARAMETER_CELL:
    case DEAL_ARM_ID_HOST_SYNC_RETURN_CELL:
        return deal_check_carrier_token(value);
    case DEAL_ARM_ID_HOST_SYNC_RETURN_NOTHING:
        return "nothing";
    default:
        deal_platform_fault("check: the check renders an unexpected arm");
    }
}

/* The inner reason of one host composite: the failing inner check's own
 * message text (P2 item 3), never a composed suffix. */
static char *deal_check_inner_reason(uint32_t case_kind,
                                     const deal_check_failure *failure,
                                     const deal_classification *value) {
    if (case_kind == DEAL_CHECK_INVALID_UNICODE) {
        /* The two INNER_ONLY host string-carrier arms, selected by the closed
         * encoding defect. */
        return deal_arm_message(value->unicode_defect == DEAL_UNICODE_SURROGATE
            ? DEAL_ARM_ID_HOST_STRING_SURROGATE
            : DEAL_ARM_ID_HOST_STRING_INVALID_UTF8, NULL, 0u);
    }
    deal_arm_parameter parameters[2];
    size_t parameter_count = 0u;
    uint32_t arm_id = deal_check_arm_of(case_kind);
    switch (arm_id) {
    case DEAL_ARM_ID_TYPED_BOUNDARY_KIND:
        parameters[0] = deal_check_parameter("kind",
            deal_descriptor_kind_text(failure->descriptor));
        parameter_count = 1u;
        break;
    case DEAL_ARM_ID_CLASS_IDENTITY:
        parameters[0] = deal_check_parameter("expected",
            deal_descriptor_class_atom(failure->descriptor));
        parameters[1] = deal_check_parameter("actual", failure->actual);
        parameter_count = 2u;
        break;
    case DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH:
        parameters[0] = deal_check_parameter("expected",
            deal_descriptor_text(failure->descriptor));
        parameters[1] = deal_check_parameter("actual", failure->actual);
        parameter_count = 2u;
        break;
    default:
        deal_platform_fault("check: no inner reason for the selected arm");
    }
    return deal_arm_message(arm_id, parameters, parameter_count);
}

/* One materialized check failure at the boundary's recorded origin. */
static deal_error *deal_check_render(uint32_t boundary_kind,
                                     const deal_check_failure *failure,
                                     const deal_classification *value,
                                     const deal_check_context *context) {
    if (boundary_kind == DEAL_BOUNDARY_HOST_PARAMETER
            || boundary_kind == DEAL_BOUNDARY_HOST_SYNC_RETURN) {
        deal_arm_parameter parameters[2];
        size_t parameter_count = 0u;
        uint32_t arm_id;
        char index_buffer[16];
        char *inner = NULL;
        if (boundary_kind == DEAL_BOUNDARY_HOST_PARAMETER) {
            inner = deal_check_inner_reason(failure->case_kind, failure, value);
            parameters[0] = deal_check_parameter("index",
                deal_check_index_text(context->parameter_index, index_buffer,
                    sizeof(index_buffer)));
            parameters[1] = deal_check_parameter("inner", inner);
            parameter_count = 2u;
            arm_id = DEAL_ARM_ID_HOST_PARAMETER_CELL;
        } else if (value->kind == DEAL_KIND_NOTHING) {
            parameters[0] = deal_check_parameter("expected",
                deal_descriptor_text(failure->cell_descriptor));
            parameter_count = 1u;
            arm_id = DEAL_ARM_ID_HOST_SYNC_RETURN_NOTHING;
        } else {
            inner = deal_check_inner_reason(failure->case_kind, failure, value);
            parameters[0] = deal_check_parameter("inner", inner);
            parameter_count = 1u;
            arm_id = DEAL_ARM_ID_HOST_SYNC_RETURN_CELL;
        }
        deal_error *error = deal_error_materialize(arm_id, parameters,
            parameter_count, deal_check_expected_of(arm_id, failure),
            deal_check_actual_of(arm_id, failure, value), context->origin, NULL);
        if (inner != NULL) {
            deal_arm_text_release(inner);
        }
        return error;
    }
    uint32_t arm_id = deal_check_boundary_arm_of(boundary_kind, failure->case_kind);
    deal_arm_parameter parameters[2];
    size_t parameter_count = deal_check_parameters(arm_id, failure, parameters);
    return deal_error_materialize(arm_id,
        parameter_count == 0u ? NULL : parameters, parameter_count,
        deal_check_expected_of(arm_id, failure),
        deal_check_actual_of(arm_id, failure, value), context->origin, NULL);
}

/* =========================================================================
 * The one descriptor-driven check (R4)
 * ========================================================================= */

static int deal_check_boundary_is_known(uint32_t boundary_kind) {
    return boundary_kind == DEAL_BOUNDARY_TYPE_DESCRIPTOR
        || boundary_kind == DEAL_BOUNDARY_FUNCTION_SIGNATURE
        || boundary_kind == DEAL_BOUNDARY_HOST_PARAMETER
        || boundary_kind == DEAL_BOUNDARY_HOST_SYNC_RETURN
        || boundary_kind == DEAL_BOUNDARY_ASYNC_COMPLETION;
}

deal_outcome deal_check_value(deal_descriptor *expected, deal_check_input input,
                              uint32_t boundary_kind,
                              const deal_check_context *context) {
    deal_outcome outcome;
    outcome.error = NULL;
    if (expected == NULL) {
        deal_platform_fault("check: null expected descriptor");
    }
    if (context == NULL || context->origin == NULL) {
        deal_platform_fault("check: missing boundary origin");
    }
    if (!deal_check_boundary_is_known(boundary_kind)) {
        deal_platform_fault("check: boundary kind outside the closed set");
    }
    /* The closed descriptor-kind rule: a function descriptor belongs to the
     * FUNCTION_SIGNATURE boundary and every other descriptor to
     * TYPE_DESCRIPTOR (D3). */
    if (boundary_kind == DEAL_BOUNDARY_TYPE_DESCRIPTOR
            && expected->kind == DEAL_DESCRIPTOR_FUNCTION) {
        deal_platform_fault("check: a function descriptor at a typed boundary");
    }
    if (boundary_kind == DEAL_BOUNDARY_FUNCTION_SIGNATURE
            && expected->kind != DEAL_DESCRIPTOR_FUNCTION) {
        deal_platform_fault("check: a non-function descriptor at a signature boundary");
    }
    if (boundary_kind == DEAL_BOUNDARY_HOST_PARAMETER
            && context->parameter_index < 1) {
        deal_platform_fault("check: a host parameter boundary needs a one-based index");
    }
    deal_classification value = deal_check_classify(&input);
    /* A result-only position completes with `nothing` where it declares
     * `void`: the successful completion projects undefined and publishes no
     * failure (C4, R4). */
    if (boundary_kind == DEAL_BOUNDARY_HOST_SYNC_RETURN
            && value.kind == DEAL_KIND_NOTHING
            && expected->kind == DEAL_DESCRIPTOR_VOID) {
        return outcome;
    }
    deal_check_failure failure;
    failure.case_kind = DEAL_CHECK_KIND_MISMATCH;
    failure.cell_descriptor = expected;
    failure.descriptor = expected;
    failure.actual = NULL;
    uint32_t case_kind = deal_check_core(expected, &value, &failure);
    if (case_kind == DEAL_CHECK_PASS) {
        return outcome;
    }
    failure.case_kind = case_kind;
    outcome.error = deal_check_render(boundary_kind, &failure, &value, context);
    return outcome;
}

/* -- the scalar boundary helpers ---------------------------------------- */

deal_outcome_number deal_check_number(const deal_data *datum,
                                      const deal_check_context *context) {
    deal_outcome_number outcome;
    outcome.value = 0.0;
    if (datum == NULL) {
        deal_platform_fault("check: null datum at a number boundary");
    }
    deal_check_input input;
    input.presentation = DEAL_PRESENT_DATUM;
    input.unicode_defect = DEAL_UNICODE_MALFORMED;
    input.datum = *datum;
    input.class_atom = NULL;
    input.signature = NULL;
    outcome.error = deal_check_value(deal_descriptor_intern("number"), input,
        DEAL_BOUNDARY_TYPE_DESCRIPTOR, context).error;
    if (outcome.error == NULL) {
        outcome.value = datum->number;
    }
    return outcome;
}

deal_outcome_boolean deal_check_boolean(const deal_data *datum,
                                        const deal_check_context *context) {
    deal_outcome_boolean outcome;
    outcome.value = 0u;
    if (datum == NULL) {
        deal_platform_fault("check: null datum at a boolean boundary");
    }
    deal_check_input input;
    input.presentation = DEAL_PRESENT_DATUM;
    input.unicode_defect = DEAL_UNICODE_MALFORMED;
    input.datum = *datum;
    input.class_atom = NULL;
    input.signature = NULL;
    outcome.error = deal_check_value(deal_descriptor_intern("boolean"), input,
        DEAL_BOUNDARY_TYPE_DESCRIPTOR, context).error;
    if (outcome.error == NULL) {
        outcome.value = datum->number != 0.0 ? 1u : 0u;
    }
    return outcome;
}

deal_outcome_string deal_check_string(const deal_data *datum,
                                      const deal_check_context *context) {
    deal_outcome_string outcome;
    outcome.value = NULL;
    if (datum == NULL) {
        deal_platform_fault("check: null datum at a string boundary");
    }
    deal_check_input input;
    input.presentation = DEAL_PRESENT_DATUM;
    input.unicode_defect = DEAL_UNICODE_MALFORMED;
    input.datum = *datum;
    input.class_atom = NULL;
    input.signature = NULL;
    outcome.error = deal_check_value(deal_descriptor_intern("string"), input,
        DEAL_BOUNDARY_TYPE_DESCRIPTOR, context).error;
    if (outcome.error == NULL) {
        outcome.value = (deal_string *)datum->reference;
    }
    return outcome;
}

/* =========================================================================
 * Class construction validation (R2, `ConstructClass`)
 * ========================================================================= */

/* The declared field of one name, or NULL when the layout declares none. */
static const deal_class_field *deal_class_field_of(const deal_class_layout *layout,
                                                   const char *name) {
    for (size_t index = 0; index < layout->field_count; index += 1u) {
        const deal_class_field *field = &layout->fields[index];
        if (field->name == NULL || field->descriptor == NULL) {
            deal_platform_fault("class construction: malformed declared field");
        }
        if (strcmp(field->name, name) == 0) {
            return field;
        }
    }
    return NULL;
}

/* The supplied value of one declared field, or NULL when it is absent. */
static const deal_class_supplied *deal_class_supplied_of(
        const deal_class_supplied *supplied, size_t supplied_count,
        const char *name) {
    for (size_t index = 0; index < supplied_count; index += 1u) {
        if (strcmp(supplied[index].name, name) == 0) {
            return &supplied[index];
        }
    }
    return NULL;
}

deal_outcome deal_class_construct(const deal_class_layout *layout,
                                  const deal_class_supplied *supplied,
                                  size_t supplied_count,
                                  const deal_origin *origin,
                                  const char **out_class_atom) {
    deal_outcome outcome;
    outcome.error = NULL;
    if (layout == NULL || layout->class_atom == NULL) {
        deal_platform_fault("class construction: null class layout");
    }
    if (layout->field_count != 0u && layout->fields == NULL) {
        deal_platform_fault("class construction: fields without a field table");
    }
    if (supplied_count != 0u && supplied == NULL) {
        deal_platform_fault("class construction: supplied fields without a table");
    }
    if (origin == NULL || out_class_atom == NULL) {
        deal_platform_fault("class construction: missing origin or out-parameter");
    }
    /* An extra supplied field is the arm's own E8007 rejection, in provided
     * source order, and it precedes every field boundary (R2). */
    for (size_t index = 0; index < supplied_count; index += 1u) {
        if (supplied[index].name == NULL) {
            deal_platform_fault("class construction: supplied field without a name");
        }
        for (size_t other = 0; other < index; other += 1u) {
            if (strcmp(supplied[other].name, supplied[index].name) == 0) {
                /* A duplicated literal key is a frontend error (R2). */
                deal_platform_fault("class construction: a field is supplied twice");
            }
        }
        if (deal_class_field_of(layout, supplied[index].name) == NULL) {
            deal_arm_parameter parameters[2];
            parameters[0] = deal_check_parameter("field", supplied[index].name);
            parameters[1] = deal_check_parameter("classId", layout->class_atom);
            outcome.error = deal_error_materialize(DEAL_ARM_ID_CLASS_EXTRA_FIELD,
                parameters, 2u, NULL, NULL, origin, NULL);
            return outcome;
        }
    }
    /* Every declared field is validated at its own recorded boundary, in
     * declaration order; the tag is published only after all of them passed,
     * so no partial instance exists (R2). */
    for (size_t index = 0; index < layout->field_count; index += 1u) {
        const deal_class_field *field = &layout->fields[index];
        if (field->name == NULL || field->descriptor == NULL) {
            deal_platform_fault("class construction: malformed declared field");
        }
        const deal_class_supplied *value =
            deal_class_supplied_of(supplied, supplied_count, field->name);
        if (value == NULL) {
            if (field->optional == 0) {
                /* A missing literal field is a frontend error (R2), so it
                 * never reaches a runtime construction. */
                deal_platform_fault("class construction: a required field is absent");
            }
            continue;
        }
        deal_check_context context;
        context.origin = &value->origin;
        context.parameter_index = 0;
        uint32_t boundary = field->descriptor->kind == DEAL_DESCRIPTOR_FUNCTION
            ? DEAL_BOUNDARY_FUNCTION_SIGNATURE : DEAL_BOUNDARY_TYPE_DESCRIPTOR;
        outcome = deal_check_value(field->descriptor, value->value, boundary,
            &context);
        if (outcome.error != NULL) {
            return outcome;
        }
    }
    *out_class_atom = layout->class_atom;
    return outcome;
}
