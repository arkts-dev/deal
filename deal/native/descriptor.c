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
#include <math.h>

#include "deal/native/container.h"
#include "deal/native/error.h"
#include "deal/native/entry.h"
#include "deal/native/host.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"

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
    size_t grown_capacity = *capacity == 0u ? 8u
        : deal_platform_checked_multiply(*capacity, 2u);
    while (grown_capacity <= needed) {
        grown_capacity = deal_platform_checked_multiply(grown_capacity, 2u);
    }
    void *grown = deal_platform_allocate(
        deal_platform_checked_multiply(grown_capacity, element_size));
    if (storage != NULL && needed != 0u) {
        memcpy(grown, storage, deal_platform_checked_multiply(needed, element_size));
        deal_platform_release(storage);
    }
    *capacity = grown_capacity;
    return grown;
}

/* One atom's text slice, copied into platform storage. */
static char *deal_descriptor_slice(const char *text, size_t start, size_t end) {
    size_t length = end - start;
    char *copy = deal_platform_allocate(deal_platform_checked_add(length, 1u));
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

deal_descriptor *deal_descriptor_intern_result(const char *descriptor_text) {
    if (descriptor_text == NULL) {
        deal_platform_fault("descriptor: null descriptor text");
    }
    size_t length = strlen(descriptor_text);
    deal_descriptor *atom = NULL;
    size_t end = deal_descriptor_parse_at(descriptor_text, length, 0u, 1, &atom);
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

deal_descriptor *const *deal_descriptor_parameters(deal_descriptor *atom,
                                                   size_t *count) {
    if (atom == NULL || atom->kind != DEAL_DESCRIPTOR_FUNCTION) {
        deal_platform_fault("descriptor: not a function descriptor");
    }
    if (count != NULL) {
        *count = atom->parameter_count;
    }
    return atom->parameters;
}

deal_descriptor *deal_descriptor_result(deal_descriptor *atom) {
    if (atom == NULL || atom->kind != DEAL_DESCRIPTOR_FUNCTION) {
        deal_platform_fault("descriptor: not a function descriptor");
    }
    if (atom->result == NULL) {
        deal_platform_fault("descriptor: a function descriptor without its"
                            " result");
    }
    return atom->result;
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
 * The `typeof` projection (spec `typeof`, page C6)
 * ========================================================================= */

/* The six names in the closed order: undefined, boolean, number, string,
 * function, object. */
#define DEAL_TYPEOF_UNDEFINED 0u
#define DEAL_TYPEOF_BOOLEAN   1u
#define DEAL_TYPEOF_NUMBER    2u
#define DEAL_TYPEOF_STRING    3u
#define DEAL_TYPEOF_FUNCTION  4u
#define DEAL_TYPEOF_OBJECT    5u
#define DEAL_TYPEOF_NAME_COUNT 6u

static const char *const deal_typeof_names[DEAL_TYPEOF_NAME_COUNT] = {
    "undefined", "boolean", "number", "string", "function", "object"
};

static deal_string *deal_typeof_strings[DEAL_TYPEOF_NAME_COUNT];

/*
 * One name, created on first use and rooted for the process lifetime: the
 * six names are shared immutable values, so a second `typeof` allocates
 * nothing and no collection reclaims a name a later projection returns.
 */
static deal_string *deal_typeof_name(uint32_t index) {
    if (deal_typeof_strings[index] != NULL) {
        return deal_typeof_strings[index];
    }
    const char *text = deal_typeof_names[index];
    deal_string *created = deal_text_new(text, strlen(text));
    deal_typeof_strings[index] = created;
    deal_static_root_register((void **)&deal_typeof_strings[index]);
    return created;
}

deal_string *deal_descriptor_type_name(const deal_data *value) {
    if (value == NULL) {
        deal_platform_fault("descriptor: null typeof datum");
    }
    switch ((deal_data_tag)value->tag) {
    case DEAL_DATA_UNDEFINED:
        return deal_typeof_name(DEAL_TYPEOF_UNDEFINED);
    case DEAL_DATA_BOOLEAN:
        return deal_typeof_name(DEAL_TYPEOF_BOOLEAN);
    case DEAL_DATA_NUMBER:
        return deal_typeof_name(DEAL_TYPEOF_NUMBER);
    case DEAL_DATA_STRING:
        return deal_typeof_name(DEAL_TYPEOF_STRING);
    case DEAL_DATA_FUNCTION:
        return deal_typeof_name(DEAL_TYPEOF_FUNCTION);
    case DEAL_DATA_NULL:
    case DEAL_DATA_ARRAY:
    case DEAL_DATA_TABLE:
    case DEAL_DATA_CLASS:
    case DEAL_DATA_ERROR:
    case DEAL_DATA_OPAQUE:
        return deal_typeof_name(DEAL_TYPEOF_OBJECT);
    }
    deal_platform_fault("descriptor: typeof of a datum tag outside the closed set");
    return NULL;
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
    int host_function;         /* unwrapped callable: never a DEAL datum */
    int opaque;                /* unsupported host object: never a DEAL datum */
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
    classification.host_function = 0;
    classification.opaque = 0;
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
    case DEAL_PRESENT_OPAQUE:
        classification.kind = DEAL_KIND_OPAQUE;
        classification.opaque = 1;
        return classification;
    case DEAL_PRESENT_RAW_FUNCTION:
        classification.kind = DEAL_KIND_FUNCTION;
        classification.host_function = 1;
        return classification;
    case DEAL_PRESENT_DATUM:
        break;
    default:
        deal_platform_fault("check: presentation outside the closed set");
    }
    classification.referent = input->datum.reference;
    switch ((deal_data_tag)input->datum.tag) {
    case DEAL_DATA_OPAQUE:
        classification.kind = DEAL_KIND_OPAQUE;
        return classification;
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
        classification.opaque = input->datum.reference != NULL
            && deal_object_layout(input->datum.reference) == DEAL_LAYOUT_HOST_OPAQUE;
        return classification;
    case DEAL_DATA_FUNCTION:
        if (input->datum.reference != NULL
                && deal_object_layout(input->datum.reference) == DEAL_LAYOUT_HOST_RAW_FUNCTION) {
            classification.kind = DEAL_KIND_FUNCTION;
            classification.host_function = 1;
            return classification;
        }
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
    case DEAL_KIND_OPAQUE:
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
    if (classification->kind == DEAL_KIND_FUNCTION && classification->signature == NULL)
        return "function";
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
    /* Every other presentation is table-carried at a host cell arm. */
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
    if (value->opaque) return DEAL_CHECK_KIND_MISMATCH;
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
        if (value->host_function) return DEAL_CHECK_KIND_MISMATCH;
        switch (value->kind) {
        case DEAL_KIND_NULL:
        case DEAL_KIND_UNDEFINED:
        case DEAL_KIND_BOOLEAN:
        case DEAL_KIND_NUMBER:
        case DEAL_KIND_STRING:
        case DEAL_KIND_TABLE:
        case DEAL_KIND_ARRAY:
        case DEAL_KIND_CLASS:
            return DEAL_CHECK_PASS;
        case DEAL_KIND_FUNCTION:
            return value->signature != NULL ? DEAL_CHECK_PASS : DEAL_CHECK_KIND_MISMATCH;
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
        if (value->kind == DEAL_KIND_FUNCTION && value->signature != NULL) {
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

/* A language boundary revalidates live descendants. This worklist holds only
 * existing objects, rooted by the caller; it allocates platform storage, not
 * managed objects, until the first failure is materialized. */
typedef struct {
    deal_check_input input;
    deal_descriptor *descriptor;
    size_t element_index;
    deal_descriptor *element_descriptor;
    deal_check_input element_input;
} deal_live_item;
typedef struct {
    deal_live_item *items;
    size_t count, capacity;
    const void **seen;
    size_t seen_count, seen_capacity;
} deal_live_walk;

static void deal_live_push(deal_live_walk *walk, deal_check_input input,
                            deal_descriptor *descriptor,
                            const deal_live_item *parent) {
    if (walk->count == walk->capacity) {
        size_t capacity = walk->capacity == 0u ? 16u
            : deal_platform_checked_multiply(walk->capacity, 2u);
        deal_live_item *items = deal_platform_allocate(
            deal_platform_checked_multiply(capacity, sizeof(*items)));
        if (items == NULL) deal_platform_fault("check: graph worklist allocation failed");
        if (walk->count != 0u) memcpy(items, walk->items,
            deal_platform_checked_multiply(walk->count, sizeof(*items)));
        deal_platform_release(walk->items);
        walk->items = items;
        walk->capacity = capacity;
    }
    deal_live_item item = {0};
    item.input = input;
    item.descriptor = descriptor;
    if (parent != NULL) {
        item.element_index = parent->element_index;
        item.element_descriptor = parent->element_descriptor;
        item.element_input = parent->element_input;
    }
    walk->items[walk->count++] = item;
}

static size_t deal_live_hash(const void *object, size_t capacity) {
    uintptr_t value = (uintptr_t)object;
    value ^= value >> 17u;
    value *= (uintptr_t)UINT64_C(0xed5ad4bb);
    value ^= value >> 11u;
    return (size_t)value & (capacity - 1u);
}

/* Insert an identity into this operation's guard; never cache admission. */
static int deal_live_seen(deal_live_walk *walk, const void *object) {
    if (walk->seen_count >= walk->seen_capacity / 2u) {
        size_t capacity = walk->seen_capacity == 0u ? 32u
            : deal_platform_checked_multiply(walk->seen_capacity, 2u);
        size_t bytes = deal_platform_checked_multiply(capacity, sizeof(*walk->seen));
        const void **seen = deal_platform_allocate(bytes);
        if (seen == NULL) deal_platform_fault("check: graph identity allocation failed");
        memset(seen, 0, bytes);
        for (size_t index = 0u; index < walk->seen_capacity; index++) {
            const void *old = walk->seen[index];
            if (old == NULL) continue;
            size_t position = deal_live_hash(old, capacity);
            while (seen[position] != NULL) position = (position + 1u) & (capacity - 1u);
            seen[position] = old;
        }
        deal_platform_release(walk->seen);
        walk->seen = seen;
        walk->seen_capacity = capacity;
    }
    size_t position = deal_live_hash(object, walk->seen_capacity);
    while (walk->seen[position] != NULL) {
        if (walk->seen[position] == object) return 1;
        position = (position + 1u) & (walk->seen_capacity - 1u);
    }
    walk->seen[position] = object;
    walk->seen_count++;
    return 0;
}

static deal_check_input deal_live_datum(deal_data datum) {
    deal_check_input input;
    deal_host_present_datum(datum, &input);
    if (datum.tag == DEAL_DATA_STRING && !deal_text_is_code_units(
            deal_text_bytes(datum.reference), deal_text_byte_length(datum.reference))) {
        input.presentation = DEAL_PRESENT_INVALID_UNICODE;
        input.unicode_defect = DEAL_UNICODE_MALFORMED;
    }
    return input;
}

static deal_check_input deal_live_element(deal_array *array, size_t index) {
    const void *slot = deal_container_array_element(array, index);
    deal_data datum;
    switch (deal_container_array_element_layout(array)) {
    case DEAL_LAYOUT_ELEMENT_DATUM:
        datum = *(const deal_data *)slot;
        break;
    case DEAL_LAYOUT_ELEMENT_NUMBER:
        datum = deal_data_of_number(*(const double *)slot);
        break;
    case DEAL_LAYOUT_ELEMENT_BOOLEAN:
        datum = deal_data_of_boolean(*(const uint8_t *)slot != 0u);
        break;
    case DEAL_LAYOUT_ELEMENT_REFERENCE: {
        void *reference = *(void *const *)slot;
        if (deal_value_is_null(reference)) datum = deal_data_of_null();
        else if (deal_value_is_undefined(reference)) datum = deal_data_of_undefined();
        else {
            uint32_t layout = deal_object_layout(reference);
            deal_data_tag tag;
            if (layout == DEAL_LAYOUT_STRING) tag = DEAL_DATA_STRING;
            else if (layout == DEAL_LAYOUT_ARRAY) tag = DEAL_DATA_ARRAY;
            else if (layout == DEAL_LAYOUT_TABLE || layout == DEAL_LAYOUT_HOST_OPAQUE)
                tag = DEAL_DATA_TABLE;
            else if (layout == DEAL_LAYOUT_ERROR) tag = DEAL_DATA_ERROR;
            else if (layout == DEAL_LAYOUT_FUNCTION || layout == DEAL_LAYOUT_HOST_RAW_FUNCTION)
                tag = DEAL_DATA_FUNCTION;
            else if (layout >= DEAL_LAYOUT_EMITTED_BASE) tag = DEAL_DATA_CLASS;
            else deal_platform_fault("check: invalid graph reference layout");
            datum = deal_data_of_reference(tag, reference);
        }
        break;
    }
    case DEAL_LAYOUT_ELEMENT_NULLISH_NUMBER: {
        const deal_nullish_number_element *value = slot;
        if (value->tag == DEAL_NULLISH_NULL) datum = deal_data_of_null();
        else if (value->tag == DEAL_NULLISH_UNDEFINED) datum = deal_data_of_undefined();
        else if (value->tag == DEAL_NULLISH_BASE) datum = deal_data_of_number(value->value);
        else deal_platform_fault("check: invalid nullish number tag");
        break;
    }
    case DEAL_LAYOUT_ELEMENT_NULLISH_BOOLEAN: {
        const deal_nullish_boolean_element *value = slot;
        if (value->tag == DEAL_NULLISH_NULL) datum = deal_data_of_null();
        else if (value->tag == DEAL_NULLISH_UNDEFINED) datum = deal_data_of_undefined();
        else if (value->tag == DEAL_NULLISH_BASE) datum = deal_data_of_boolean(value->value != 0u);
        else deal_platform_fault("check: invalid nullish boolean tag");
        break;
    }
    default:
        deal_platform_fault("check: invalid graph element layout");
    }
    return deal_live_datum(datum);
}

static deal_outcome deal_check_live_value_core(deal_descriptor *expected, deal_check_input input,
                                   uint32_t boundary_kind,
                                   const deal_check_context *context, int wrap_elements) {
    deal_outcome outcome = deal_check_value(expected, input, boundary_kind, context);
    if (outcome.error != NULL || input.presentation != DEAL_PRESENT_DATUM) return outcome;
    deal_live_walk walk = {0};
    deal_live_push(&walk, input, expected, NULL);
    deal_live_item item = {0};
    while (walk.count != 0u) {
        item = walk.items[--walk.count];
        deal_descriptor *descriptor = item.descriptor;
        /* Unknown graph leaves still own their concrete string encoding check. */
        if (descriptor->kind == DEAL_DESCRIPTOR_UNKNOWN
                && item.input.presentation == DEAL_PRESENT_INVALID_UNICODE)
            descriptor = deal_descriptor_intern("string");
        uint32_t boundary = descriptor->kind == DEAL_DESCRIPTOR_FUNCTION
            ? DEAL_BOUNDARY_FUNCTION_SIGNATURE : DEAL_BOUNDARY_TYPE_DESCRIPTOR;
        outcome = deal_check_value(descriptor, item.input, boundary, context);
        if (outcome.error != NULL) break;
        if (item.input.presentation != DEAL_PRESENT_DATUM) continue;
        deal_data datum = item.input.datum;
        if (!deal_data_is_managed(&datum)) continue;
        if (datum.tag == DEAL_DATA_FUNCTION) {
            if (deal_fn_thunk_of(datum.reference) == NULL) {
                deal_classification value = deal_check_classify(&item.input);
                deal_check_failure failure = {DEAL_CHECK_KIND_MISMATCH,
                    deal_descriptor_intern("()->unknown"), deal_descriptor_intern("()->unknown"), "function"};
                outcome.error = deal_check_render(DEAL_BOUNDARY_TYPE_DESCRIPTOR, &failure, &value, context);
                break;
            }
            continue;
        }
        if (datum.tag == DEAL_DATA_STRING) {
            deal_check_input text = deal_live_datum(datum);
            if (text.presentation != DEAL_PRESENT_DATUM) {
                outcome = deal_check_value(deal_descriptor_intern("string"), text,
                    DEAL_BOUNDARY_TYPE_DESCRIPTOR, context);
                break;
            }
            continue;
        }
        if (deal_live_seen(&walk, datum.reference)) continue;
        if (datum.tag == DEAL_DATA_TABLE) {
            deal_table *table = datum.reference;
            /* Reverse push preserves insertion order during the depth-first walk. */
            size_t count = deal_container_table_count(table);
            for (size_t index = count; index != 0u; index--) {
                deal_string *key = deal_container_table_key_at(table, index - 1u);
                deal_live_push(&walk, deal_live_datum(deal_container_table_read(table, key)),
                    deal_descriptor_intern("unknown"), &item);
                deal_live_push(&walk, deal_live_datum(deal_data_of_reference(DEAL_DATA_STRING, key)),
                    deal_descriptor_intern("string"), &item);
            }
        } else if (datum.tag == DEAL_DATA_ARRAY) {
            deal_array *array = datum.reference;
            deal_descriptor *declared = descriptor;
            while (declared->kind == DEAL_DESCRIPTOR_NULLISH) declared = declared->inner;
            deal_descriptor *element = declared->kind == DEAL_DESCRIPTOR_ARRAY
                ? declared->inner : deal_descriptor_intern("unknown");
            for (size_t index = deal_container_array_count(array); index != 0u; index--) {
                deal_check_input child = deal_live_element(array, index - 1u);
                deal_live_push(&walk, child, element, &item);
                deal_live_item *pushed = &walk.items[walk.count - 1u];
                if (declared->kind == DEAL_DESCRIPTOR_ARRAY && pushed->element_index == 0u) {
                    pushed->element_index = index;
                    pushed->element_descriptor = element;
                    pushed->element_input = child;
                }
            }
        } else if (datum.tag == DEAL_DATA_ERROR) {
            /* Builtin fields live in error.c's platform text, not an emitted
             * class record. Inspect without allocating an admitted string from
             * possibly hostile bytes. The Error itself remains unchanged. */
            for (size_t index = 0u; index < 2u; index++) {
                const char *bytes = NULL;
                int present = index == 0u
                    ? deal_error_code(datum.reference, &bytes)
                    : deal_error_message(datum.reference, &bytes);
                if (present && deal_text_is_code_units(bytes, strlen(bytes))) continue;
                deal_check_input field = {0};
                field.presentation = present ? DEAL_PRESENT_INVALID_UNICODE : DEAL_PRESENT_MISSING;
                field.unicode_defect = DEAL_UNICODE_MALFORMED;
                outcome = deal_check_value(deal_descriptor_intern("string"), field,
                    DEAL_BOUNDARY_TYPE_DESCRIPTOR, context);
                break;
            }
            if (outcome.error != NULL) break;
        } else if (datum.tag == DEAL_DATA_CLASS) {
            const deal_host_class_access *access = deal_host_class_access_of(item.input.class_atom);
            if (access == NULL) deal_platform_fault("check: class has no declared access plan");
            deal_descriptor *declared = descriptor;
            while (declared->kind == DEAL_DESCRIPTOR_NULLISH) declared = declared->inner;
            int typed_class = declared->kind == DEAL_DESCRIPTOR_CLASS;
            for (size_t index = access->declared->field_count; index != 0u; index--) {
                const deal_class_field *field = &access->declared->fields[index - 1u];
                deal_check_input child = {0};
                uint8_t present = 0u;
                if (!access->read(&datum, index - 1u, &child, &present, access->context))
                    deal_platform_fault("check: class access rejected its instance");
                if (present == 0u && (field->optional || !typed_class)) continue;
                if (present == 0u) {
                    child.presentation = DEAL_PRESENT_MISSING;
                    deal_classification missing = deal_check_classify(&child);
                    deal_check_failure failure = {DEAL_CHECK_KIND_MISMATCH,
                        field->descriptor, field->descriptor, "undefined"};
                    outcome.error = deal_check_render(DEAL_BOUNDARY_TYPE_DESCRIPTOR,
                        &failure, &missing, context);
                    break;
                }
                if (child.presentation == DEAL_PRESENT_DATUM)
                    child = deal_live_datum(child.datum);
                deal_live_push(&walk, child, typed_class ? field->descriptor
                    : deal_descriptor_intern("unknown"), &item);
            }
            if (outcome.error != NULL) break;
        }
    }
    if (wrap_elements && outcome.error != NULL && item.element_index != 0u) {
        /* The outer checked array owns its first failing element; nested
         * arrays propagate the leaf cause without adding another wrapper. */
        char index_text[3u * sizeof(size_t) + 1u];
        size_t end = sizeof(index_text) - 1u;
        index_text[end] = '\0';
        size_t index = item.element_index;
        do {
            index_text[--end] = (char)('0' + index % 10u);
            index /= 10u;
        } while (index != 0u);
        deal_arm_parameter parameter = {"oneBasedIndex", &index_text[end]};
        outcome.error = deal_error_materialize(DEAL_ARM_ID_ARRAY_ELEMENT_KIND,
            &parameter, 1u, deal_descriptor_text(item.element_descriptor),
            deal_check_token(&item.element_input), context->origin, outcome.error);
    }
    deal_platform_release(walk.items);
    deal_platform_release(walk.seen);
    return outcome;
}

deal_outcome deal_check_live_value(deal_descriptor *expected, deal_check_input input,
                                   uint32_t boundary_kind,
                                   const deal_check_context *context) {
    return deal_check_live_value_core(expected, input, boundary_kind, context, 1);
}

deal_outcome_data deal_array_pop(deal_array *array, deal_descriptor *element,
                                  const deal_origin *origin) {
    deal_outcome_data result = {0};
    result.value = deal_data_of_undefined();
    size_t count = deal_container_array_count(array);
    if (count == 0u) return result;
    deal_check_input input = deal_live_element(array, count - 1u);
    deal_check_context context = {0};
    context.origin = origin;
    uint32_t boundary = deal_descriptor_type(element) == DEAL_DESCRIPTOR_FUNCTION
        ? DEAL_BOUNDARY_FUNCTION_SIGNATURE : DEAL_BOUNDARY_TYPE_DESCRIPTOR;
    /* Pop owns the outer position even when the removed value is an array. */
    deal_outcome checked = deal_check_live_value_core(element, input, boundary, &context, 0);
    if (checked.error != NULL) {
        char index_text[3u * sizeof(size_t) + 1u];
        size_t end = sizeof(index_text) - 1u;
        index_text[end] = '\0';
        size_t index = count;
        do {
            index_text[--end] = (char)('0' + index % 10u);
            index /= 10u;
        } while (index != 0u);
        deal_arm_parameter parameter = {"oneBasedIndex", &index_text[end]};
        result.error = deal_error_materialize(DEAL_ARM_ID_ARRAY_ELEMENT_KIND,
            &parameter, 1u, deal_descriptor_text(element), deal_check_token(&input),
            origin, checked.error);
        return result;
    }
    result.value = input.datum;
    deal_container_array_remove_last(array);
    return result;
}

deal_outcome_data deal_array_splice(deal_array *array, double from, double delete_count,
                                     deal_descriptor *element, const deal_origin *origin) {
    deal_outcome_data result = {0};
    size_t length = deal_container_array_count(array);
    double integer = isnan(from) ? 0.0 : trunc(from);
    double first = integer < 0.0 ? fmax(0.0, (double)length + integer)
                                : fmin((double)length, integer);
    size_t start = (size_t)first;
    size_t count = isnan(delete_count) || delete_count <= 0.0 ? 0u
        : (size_t)fmin((double)(length - start), trunc(delete_count));
    deal_root_scope_push();
    (void)deal_root_scope_hold(array);
    for (size_t i = 0u; i < count; i++) {
        deal_check_input input = deal_live_element(array, start + i);
        deal_check_context context = {0};
        context.origin = origin;
        uint32_t boundary = deal_descriptor_type(element) == DEAL_DESCRIPTOR_FUNCTION
            ? DEAL_BOUNDARY_FUNCTION_SIGNATURE : DEAL_BOUNDARY_TYPE_DESCRIPTOR;
        deal_outcome checked = deal_check_live_value_core(element, input, boundary, &context, 0);
        if (checked.error != NULL) {
            char index_text[3u * sizeof(size_t) + 1u];
            size_t end = sizeof(index_text) - 1u;
            index_text[end] = '\0';
            size_t index = start + i + 1u;
            do { index_text[--end] = (char)('0' + index % 10u); index /= 10u; } while (index != 0u);
            deal_arm_parameter parameter = {"oneBasedIndex", &index_text[end]};
            result.error = deal_error_materialize(DEAL_ARM_ID_ARRAY_ELEMENT_KIND,
                &parameter, 1u, deal_descriptor_text(element), deal_check_token(&input), origin, checked.error);
            deal_root_scope_pop();
            return result;
        }
    }
    deal_array *removed = deal_container_array_new_typed(deal_container_array_element_layout(array),
        deal_descriptor_reference(element), count);
    (void)deal_root_scope_hold(removed);
    for (size_t i = 0u; i < count; i++)
        deal_container_array_store(removed, i, deal_container_array_element(array, start + i));
    deal_container_array_remove_range(array, start, count);
    result.value = deal_data_of_reference(DEAL_DATA_ARRAY, removed);
    deal_root_scope_pop();
    return result;
}

/* -- the typed-boundary token (R4) -------------------------------------- */

const char *deal_check_token(const deal_check_input *input) {
    deal_classification value = deal_check_classify(input);
    return deal_check_typed_token(&value);
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
