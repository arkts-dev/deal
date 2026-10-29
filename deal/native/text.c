/*
 * deal/native/text.c — the `text` service group implementation.
 *
 * Immutable UTF-8 string storage with its cached byte length, scalar count and
 * hash, the strict Unicode scalar-sequence validation every untyped boundary
 * runs, the one scalar-sequence comparison and the data `===` relation of
 * `text.h` (`llvm-native-runtime-value-model` R2/R8, `llvm-native-codegen-
 * cfg-ssa` C6).
 */
#include "deal/native/text.h"

#include <string.h>

#include "deal/native/memory.h"
#include "deal/native/platform.h"

/* =========================================================================
 * The immutable string representation
 * ========================================================================= */

/*
 * A string is one managed object: the three cached facts followed by the exact
 * UTF-8 bytes. The bytes are never NUL-terminated (the byte length is
 * authoritative), the buffer is never written after construction, and the
 * layout declares no managed-reference slot.
 */
struct deal_string {
    size_t byte_length;
    size_t scalar_count;
    uint64_t hash;
    unsigned char bytes[];
};

static const deal_layout deal_string_layout = {
    DEAL_LAYOUT_STRING, 0u, sizeof(struct deal_string), NULL, 0u
};

/* The FNV-1a 64-bit content hash over the exact bytes (deterministic). */
#define DEAL_TEXT_HASH_OFFSET_BASIS UINT64_C(14695981039346656037)
#define DEAL_TEXT_HASH_PRIME        UINT64_C(1099511628211)

static uint64_t deal_text_hash_bytes(const unsigned char *bytes,
                                     size_t length) {
    uint64_t hash = DEAL_TEXT_HASH_OFFSET_BASIS;
    for (size_t index = 0; index < length; index += 1u) {
        hash ^= (uint64_t)bytes[index];
        hash *= DEAL_TEXT_HASH_PRIME;
    }
    return hash;
}

/* The scalar count of one validated sequence: every non-continuation byte
 * starts exactly one scalar value. */
static size_t deal_text_count_scalars(const unsigned char *bytes,
                                      size_t length) {
    size_t count = 0u;
    for (size_t index = 0; index < length; index += 1u) {
        if ((bytes[index] & 0xC0u) != 0x80u) {
            count += 1u;
        }
    }
    return count;
}

/* 1 when `index + 1 .. index + count` are continuation bytes. */
static int deal_text_continuations(const unsigned char *bytes, size_t length,
                                   size_t index, size_t count) {
    if (length - index <= count) {
        return 0;
    }
    for (size_t offset = 1u; offset <= count; offset += 1u) {
        if ((bytes[index + offset] & 0xC0u) != 0x80u) {
            return 0;
        }
    }
    return 1;
}

int deal_text_is_scalar_sequence(const void *bytes, size_t length) {
    if (length == 0u) {
        return 1;
    }
    if (bytes == NULL) {
        return 0;
    }
    const unsigned char *cursor = bytes;
    size_t index = 0u;
    while (index < length) {
        unsigned char lead = cursor[index];
        if (lead < 0x80u) {
            index += 1u;
            continue;
        }
        if (lead >= 0xC2u && lead <= 0xDFu) {
            if (!deal_text_continuations(cursor, length, index, 1u)) {
                return 0;
            }
            index += 2u;
            continue;
        }
        if (lead >= 0xE0u && lead <= 0xEFu) {
            if (!deal_text_continuations(cursor, length, index, 2u)) {
                return 0;
            }
            unsigned char second = cursor[index + 1u];
            if (lead == 0xE0u && second < 0xA0u) {
                return 0;   /* overlong */
            }
            if (lead == 0xEDu && second >= 0xA0u) {
                return 0;   /* surrogate code unit */
            }
            index += 3u;
            continue;
        }
        if (lead >= 0xF0u && lead <= 0xF4u) {
            if (!deal_text_continuations(cursor, length, index, 3u)) {
                return 0;
            }
            unsigned char second = cursor[index + 1u];
            if (lead == 0xF0u && second < 0x90u) {
                return 0;   /* overlong */
            }
            if (lead == 0xF4u && second >= 0x90u) {
                return 0;   /* above U+10FFFF */
            }
            index += 4u;
            continue;
        }
        return 0;           /* 0x80..0xC1 lead and 0xF5..0xFF are never valid */
    }
    return 1;
}

deal_string *deal_text_new(const void *bytes, size_t length) {
    if (bytes == NULL && length != 0u) {
        deal_platform_fault("text: null bytes with a non-zero length");
    }
    if (!deal_text_is_scalar_sequence(bytes, length)) {
        deal_platform_fault("text: bytes are not a Unicode scalar sequence");
    }
    if (length > SIZE_MAX - sizeof(struct deal_string)) {
        deal_platform_fault("text: string size overflow");
    }
    deal_layout_define(&deal_string_layout);
    deal_string *string = deal_alloc(DEAL_LAYOUT_STRING,
                                     sizeof(struct deal_string) + length);
    string->byte_length = length;
    string->scalar_count = 0u;
    string->hash = DEAL_TEXT_HASH_OFFSET_BASIS;
    if (length != 0u) {
        const unsigned char *source = bytes;
        memcpy(string->bytes, source, length);
        string->scalar_count = deal_text_count_scalars(string->bytes, length);
        string->hash = deal_text_hash_bytes(string->bytes, length);
    }
    return string;
}

/* One string's storage; a non-string pointer is an invariant fault. */
static const deal_string *deal_text_of(const deal_string *string) {
    if (string == NULL) {
        deal_platform_fault("text: null string");
    }
    if (deal_object_layout(string) != DEAL_LAYOUT_STRING) {
        deal_platform_fault("text: not a string");
    }
    return string;
}

const unsigned char *deal_text_bytes(const deal_string *string) {
    return deal_text_of(string)->bytes;
}

size_t deal_text_byte_length(const deal_string *string) {
    return deal_text_of(string)->byte_length;
}

size_t deal_text_scalar_length(const deal_string *string) {
    return deal_text_of(string)->scalar_count;
}

size_t deal_text_scalar_offset(const deal_string *string, size_t scalar_index) {
    const deal_string *text = deal_text_of(string);
    if (scalar_index > text->scalar_count) {
        deal_platform_fault("text: scalar position outside the scalar count");
    }
    size_t scalar = 0u;
    size_t index = 0u;
    while (scalar < scalar_index) {
        unsigned char lead = text->bytes[index];
        if (lead < 0x80u) {
            index += 1u;
        } else if (lead < 0xE0u) {
            index += 2u;
        } else if (lead < 0xF0u) {
            index += 3u;
        } else {
            index += 4u;
        }
        scalar += 1u;
    }
    return index;
}

uint64_t deal_text_hash(const deal_string *string) {
    return deal_text_of(string)->hash;
}

/* =========================================================================
 * Scalar-sequence comparison (C6, R8)
 * ========================================================================= */

/* Decodes one scalar value at `index` of a validated sequence. */
static uint32_t deal_text_scalar_at(const unsigned char *bytes, size_t index,
                                    size_t *advance) {
    unsigned char lead = bytes[index];
    if (lead < 0x80u) {
        *advance = 1u;
        return lead;
    }
    if (lead < 0xE0u) {
        *advance = 2u;
        return ((uint32_t)(lead & 0x1Fu) << 6)
            | (uint32_t)(bytes[index + 1u] & 0x3Fu);
    }
    if (lead < 0xF0u) {
        *advance = 3u;
        return ((uint32_t)(lead & 0x0Fu) << 12)
            | ((uint32_t)(bytes[index + 1u] & 0x3Fu) << 6)
            | (uint32_t)(bytes[index + 2u] & 0x3Fu);
    }
    *advance = 4u;
    return ((uint32_t)(lead & 0x07u) << 18)
        | ((uint32_t)(bytes[index + 1u] & 0x3Fu) << 12)
        | ((uint32_t)(bytes[index + 2u] & 0x3Fu) << 6)
        | (uint32_t)(bytes[index + 3u] & 0x3Fu);
}

deal_string *deal_text_concat(deal_string *left, deal_string *right) {
    const deal_string *first = deal_text_of(left);
    const deal_string *second = deal_text_of(right);
    if (first->byte_length > SIZE_MAX - second->byte_length) {
        deal_platform_fault("text: concatenation size overflow");
    }
    size_t length = first->byte_length + second->byte_length;
    if (length > SIZE_MAX - sizeof(struct deal_string)) {
        deal_platform_fault("text: concatenation size overflow");
    }
    deal_layout_define(&deal_string_layout);
    deal_string *joined = deal_alloc(DEAL_LAYOUT_STRING,
                                     sizeof(struct deal_string) + length);
    joined->byte_length = length;
    joined->scalar_count = first->scalar_count + second->scalar_count;
    if (first->byte_length != 0u) {
        memcpy(joined->bytes, first->bytes, first->byte_length);
    }
    if (second->byte_length != 0u) {
        memcpy(joined->bytes + first->byte_length, second->bytes,
               second->byte_length);
    }
    joined->hash = deal_text_hash_bytes(joined->bytes, length);
    return joined;
}

int deal_text_compare(deal_string *a, deal_string *b) {
    const deal_string *left = deal_text_of(a);
    const deal_string *right = deal_text_of(b);
    size_t left_index = 0u;
    size_t right_index = 0u;
    while (left_index < left->byte_length
            && right_index < right->byte_length) {
        size_t left_advance = 0u;
        size_t right_advance = 0u;
        uint32_t left_scalar =
            deal_text_scalar_at(left->bytes, left_index, &left_advance);
        uint32_t right_scalar =
            deal_text_scalar_at(right->bytes, right_index, &right_advance);
        if (left_scalar < right_scalar) {
            return -1;
        }
        if (left_scalar > right_scalar) {
            return 1;
        }
        left_index += left_advance;
        right_index += right_advance;
    }
    /* A proper prefix sorts first; equal sequences compare equal. */
    if (left_index < left->byte_length) {
        return 1;
    }
    if (right_index < right->byte_length) {
        return -1;
    }
    return 0;
}

/* =========================================================================
 * The data `===` relation (R2)
 * ========================================================================= */

int deal_data_same(const deal_data *a, const deal_data *b) {
    if (a == NULL || b == NULL) {
        deal_platform_fault("data: null datum in a comparison");
    }
    if (a->tag != b->tag) {
        return 0;
    }
    switch ((deal_data_tag)a->tag) {
    case DEAL_DATA_UNDEFINED:
    case DEAL_DATA_NULL:
        return 1;
    case DEAL_DATA_BOOLEAN:
        return (a->number != 0.0) == (b->number != 0.0);
    case DEAL_DATA_NUMBER:
        return a->number == b->number;
    case DEAL_DATA_STRING:
        return deal_text_compare((deal_string *)a->reference,
                                 (deal_string *)b->reference) == 0;
    case DEAL_DATA_ARRAY:
    case DEAL_DATA_TABLE:
    case DEAL_DATA_CLASS:
    case DEAL_DATA_FUNCTION:
    case DEAL_DATA_ERROR:
        return a->reference == b->reference;
    }
    deal_platform_fault("data: tag outside the closed set");
}
