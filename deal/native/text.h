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
 * Text ingress (R2). A string that DEAL code holds at a declared `string`
 * position is valid by construction, and every string arriving from an untyped
 * boundary (a host crossing, a JSON decode, a boxed carrier read at a declared
 * type) is validated as a Unicode scalar sequence at that boundary by
 * `deal_text_is_scalar_sequence`. `deal_text_new` therefore accepts exactly a
 * validated scalar sequence: malformed bytes never become an admitted
 * `deal_string`, and a caller that skips its boundary check is reported as the
 * deterministic invariant fault instead of publishing malformed text.
 *
 * The data `===` relation is declared here rather than in `value.h` because
 * string equality is the one scalar-sequence comparison this group owns: the
 * `text` group depends on `value`, never the other way round.
 */
#ifndef DEAL_NATIVE_TEXT_H
#define DEAL_NATIVE_TEXT_H

#include "deal/native/deal_rt.h"

/*
 * 1 when `bytes` is a well-formed UTF-8 sequence of Unicode scalar values
 * (RFC 3629: no overlong form, no surrogate, nothing above U+10FFFF and no
 * truncated sequence), 0 otherwise. A null `bytes` with a non-zero length is
 * malformed, never empty.
 */
int deal_text_is_scalar_sequence(const void *bytes, size_t length);

/*
 * Builds a string from `length` validated bytes: this entry copies them and
 * caches the byte length, scalar count and hash, so the storage is immutable.
 * Bytes that are not a Unicode scalar sequence are an invariant fault (an
 * untyped boundary validates first and raises its own arm).
 */
deal_string *deal_text_new(const void *bytes, size_t length);

/* The single scalar-value sequence comparison: -1, 0 or 1 (C6, R8). */
int deal_text_compare(deal_string *a, deal_string *b);

/* The bytes (never NUL-terminated by contract) and their exact length. */
const unsigned char *deal_text_bytes(const deal_string *string);
size_t deal_text_byte_length(const deal_string *string);

/* The exact Unicode scalar count. */
size_t deal_text_scalar_length(const deal_string *string);

/*
 * The byte offset of one scalar position: the offset of scalar `scalar_index`
 * in `0..scalar count`, so the final position answers the byte length. The
 * range `[offset(i), offset(i + 1))` is exactly scalar `i`'s UTF-8 encoding,
 * and the range `[offset(lo), offset(hi))` is the scalar slice `[lo, hi)`.
 *
 * Only this group decodes UTF-8, so no consumer derives a scalar boundary from
 * raw bytes: the string operations of the `std` group index through this entry
 * (R8) and never split a multi-byte encoding. An out-of-range position is an
 * invariant fault.
 */
size_t deal_text_scalar_offset(const deal_string *string, size_t scalar_index);

/* The cached content hash. */
uint64_t deal_text_hash(const deal_string *string);

/*
 * The language `===` relation over two persistent data values (R2, spec
 * `===`/`!==`): equal tags are required; `null` and `undefined` equal
 * themselves only; numbers use binary64 equality (so NaN differs from itself
 * and signed zeros are equal); booleans compare by value; strings compare by
 * Unicode scalar sequence through `deal_text_compare`; arrays, records, class
 * instances, function values and Errors compare by reference identity. No
 * coercion, retagging or structural comparison happens here.
 */
int deal_data_same(const deal_data *a, const deal_data *b);

#endif /* DEAL_NATIVE_TEXT_H */
