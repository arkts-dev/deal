/*
 * deal/native/json.h — the `json` service group.
 *
 * One JSON service group implements the shared JSON rules
 * (`llvm-native-runtime-value-model` R6): strict grammar scanning with one-based
 * UTF-8 byte positions, duplicate decoded-key rejection per object, JSON
 * numeric tokens parsed directly to `number` (IEEE 754 binary64, round to
 * nearest, ties to even, signed zero and numeric overflow/underflow preserved),
 * scalar-valid strings with surrogate and malformed-UTF-8 rejection, stack-safe
 * traversal without a fixed nesting cap, cycle detection by path-local active
 * identity on encode, and rejection of non-finite serialized numbers.
 *
 * `C$fromJson`/`C$toJson` run the emitted class walk over these entries; the
 * walk's field ordering, optional-absence preservation and `JSON_FROM_FIELD`/
 * `JSON_TO_FIELD` boundaries belong to the emitted class plans and to the
 * runtime-boundaries-errors child, which extends this header.
 */
#ifndef DEAL_NATIVE_JSON_H
#define DEAL_NATIVE_JSON_H

#include "deal/native/deal_rt.h"

/* Parses one JSON text into a value graph; a syntax, duplicate-key or encoding
 * failure is the recorded failure arm at `origin`. */
deal_outcome_value deal_json_parse(deal_string *text, const deal_origin *origin);

/* Serializes one value graph into JSON text; a cycle or a non-finite number is
 * the recorded failure arm at `origin` and exposes no partial output. */
deal_outcome_string deal_json_stringify(deal_value value,
                                       const deal_origin *origin);

#endif /* DEAL_NATIVE_JSON_H */
