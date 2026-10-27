/*
 * deal/native/text.h — the `text` service group.
 *
 * Strings, UTF-8 and Unicode scalar handling (`llvm-native-runtime-value-
 * model` R2, R8). A `deal_string` is an immutable UTF-8 buffer with its cached
 * byte length, Unicode scalar count and hash. String operations are defined
 * over Unicode scalar values, and the one scalar-sequence comparison is
 * `deal_text_compare`, the single authority the emitted comparison selectors
 * (STRING_EQ/NE/LT/LE/GT/GE) call (`llvm-native-codegen-cfg-ssa` C6): no
 * per-site comparison code exists anywhere else.
 *
 * The group's remaining entries (substring, split, replace, trim, the
 * validation entry every untyped boundary uses) arrive with the
 * runtime-boundaries-errors child, which extends this header.
 */
#ifndef DEAL_NATIVE_TEXT_H
#define DEAL_NATIVE_TEXT_H

#include "deal/native/deal_rt.h"

/*
 * Builds a string from `length` bytes. The caller has already validated the
 * bytes as a Unicode scalar sequence at its untyped boundary (R2); this entry
 * copies them and caches the byte length, scalar count and hash.
 */
deal_string *deal_text_new(const void *bytes, size_t length);

/* The single scalar-value sequence comparison: -1, 0 or 1 (C6, R8). */
int deal_text_compare(deal_string *a, deal_string *b);

/* The bytes (never NUL-terminated by contract) and their exact length. */
const unsigned char *deal_text_bytes(const deal_string *string);
size_t deal_text_byte_length(const deal_string *string);

/* The exact Unicode scalar count. */
size_t deal_text_scalar_length(const deal_string *string);

/* The cached content hash. */
uint64_t deal_text_hash(const deal_string *string);

#endif /* DEAL_NATIVE_TEXT_H */
