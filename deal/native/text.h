/* Immutable UTF-16 strings and their lossless byte carriers. */
#ifndef DEAL_NATIVE_TEXT_H
#define DEAL_NATIVE_TEXT_H

#include "deal/native/deal_rt.h"

/* Strict scalar UTF-8 validation for channels whose encoding is UTF-8. */
int deal_text_is_scalar_sequence(const void *bytes, size_t length);

/* Well-formed WTF-8 admission: lone UTF-16 units are permitted; overlong,
 * truncated, stray-continuation and out-of-range encodings are rejected. */
int deal_text_is_code_units(const void *bytes, size_t length);

/* Copies admitted UTF-8/WTF-8 into canonical per-unit storage. Supplementary
 * scalars become two three-byte units. A malformed input is an invariant fault;
 * untyped boundaries validate before construction and own their failure arms. */
deal_string *deal_text_new(const void *bytes, size_t length);

/* Callers root operands across allocation. Joining a high and low unit creates
 * one iteration element without changing either operand or its code units. */
deal_string *deal_text_concat(deal_string *left, deal_string *right);

/* Lexicographic UTF-16 ordering, without normalization or locale rules. */
int deal_text_compare(deal_string *a, deal_string *b);

/* Canonical per-unit bytes; never NUL-terminated. */
const unsigned char *deal_text_bytes(const deal_string *string);
size_t deal_text_byte_length(const deal_string *string);

/* Cached host WTF-8 view: pairs combine, lone units retain three-byte encoding.
 * Its lifetime is the string's lifetime; obtaining it never allocates. */
const unsigned char *deal_text_transport_bytes(const deal_string *string);
size_t deal_text_transport_length(const deal_string *string);

/* UTF-16 length and unit-to-byte positions; final position answers byte length. */
size_t deal_text_unit_length(const deal_string *string);
size_t deal_text_unit_offset(const deal_string *string, size_t unit_index);

/* JavaScript iteration count and element-to-byte positions: a high/low pair is
 * one element, a lone unit is one element. Out-of-range positions fault. */
size_t deal_text_scalar_length(const deal_string *string);
size_t deal_text_scalar_offset(const deal_string *string, size_t scalar_index);

/* Cached FNV-1a content hash of canonical unit bytes. */
uint64_t deal_text_hash(const deal_string *string);

/* Same-tag data equality: binary64 numbers, booleans, UTF-16 strings,
 * nullish singletons, and reference identity for aggregate/function values. */
int deal_data_same(const deal_data *a, const deal_data *b);

#endif /* DEAL_NATIVE_TEXT_H */
