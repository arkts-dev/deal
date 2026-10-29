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
 * Decoded graphs are persistent tagged data (`deal_data`), never crossing
 * handles: a decoded JSON object is a record, a decoded array records the
 * `unknown` element descriptor (spec v1.5 `T[]`: "json.parse arrays ...
 * retain unknown descriptors"), and a decoded `null` token is the language null
 * datum, so JSON decoding never creates stored `undefined`.
 *
 * Failures. The two parse/encode entries materialize their failures from the
 * delivered arm table through the `error` group: the parse entry publishes the
 * `JSON_PARSE_ERROR` arm, the generic encoder the `JSON_STRINGIFY_UNSUPPORTED`
 * arm and the walk family's closed cycle arm, and the class encode walk the
 * `JSON_TO_WALK` arm. This group authors no failure text, no expected/actual
 * projection and no second descriptor check: the class walks validate their
 * fields through the delivered `deal_check_value` at the `JSON_FROM_FIELD` and
 * `JSON_TO_FIELD` boundaries under the caller's own `deal_origin`.
 *
 * The class walks (`C$fromJson`/`C$toJson`) are emitted as one plan per class
 * (C2 "per-layout class/JSON plans"): the emitted module supplies the declared
 * field layout (declaration order, from the `descriptor` group's
 * `deal_class_layout`) plus the two construction seams this group drives —
 * instance construction from already validated field data and field extraction
 * from an instance — and this group owns the declaration-order walk, the extra
 * key and required-presence rules, the descent through nested class plans on
 * an explicit frame stack (the input's nesting depth is never a C frame, so a
 * recursive `@jsonable` plan and a deeply nested document both decode), the
 * path-local cycle check and the invocation-local scratch. A declared `T[]`
 * field is rebuilt with its declared element descriptor beside the
 * runtime-owned element-storage layout of that element type (C3, R2), so a
 * decoded array is a value of its declared type: the emitted element access
 * reads the same storage whether the module or this walk built the array. A
 * declared `null`/`undefined` element type is the reference storage holding the
 * distinct runtime singleton, and the canonical `null|undefined` atom admits
 * its undefined member through the atom's own base.
 *
 * `C$fromJson` returns language null on any parse, unknown-key, missing
 * required-field or field-validation failure (`JSON_FROM_NULL`: the swallowed
 * failure never escapes and no partial instance is published). `C$toJson`
 * publishes the encoded text or the arm-rendered failure, never partial output.
 */
#ifndef DEAL_NATIVE_JSON_H
#define DEAL_NATIVE_JSON_H

#include "deal/native/deal_rt.h"

/* -- the parse and generic-encode entries (std/json) --------------------- */

/*
 * Parses one JSON text into a value graph and answers it as persistent tagged
 * data. A syntax defect, a duplicate decoded member key or an encoding defect
 * is the installed `JSON_PARSE_ERROR` arm at `origin` with the defect's
 * one-based UTF-8 byte offset and its closed reason text; the top-level value
 * may be any JSON value (the `std` group's `record` return boundary decides
 * the root shape, never this entry).
 */
deal_outcome_data deal_json_parse(deal_string *text, const deal_origin *origin);

/*
 * The generic `std/json.stringify(unknown)` encoder. Answers the encoded text
 * as a string datum, or the undefined datum when the root value carries no
 * JSON representation (top-level `undefined` or a function value). Encoding
 * follows spec v1.5: non-finite numbers become `null`, finite output numbers
 * roundtrip to the same binary64 value with negative zero as `0`, `null` is
 * preserved, `undefined`/function-valued object members are omitted while such
 * array elements encode as `null`, a record's own keys encode array-index keys
 * ascending first and then in creation order, and a path-local container
 * re-entry is the walk family's cycle arm; shared acyclic children encode
 * twice. An unsupported value kind (a class instance, a builtin `Error`, an
 * async-operation presentation) is the `JSON_STRINGIFY_UNSUPPORTED` arm with
 * the value's carrier-kind token. Neither arm exposes partial output.
 */
deal_outcome_data deal_json_stringify(deal_data value,
                                      const deal_origin *origin);

/* -- the emitted class walks (`C$fromJson` / `C$toJson`) ------------------ */

/*
 * One emitted class plan's construction seam (`C$fromJson`): builds one
 * instance of the plan's class from the already validated and decoded fields.
 * `values[i]` and `presence[i]` belong to `layout->fields[i]`: a present field
 * carries its decoded persistent datum, an absent optional carries presence 0
 * and no value. The walk calls it after every field of the object validated,
 * so the seam never observes a partial or rejected request.
 */
typedef deal_outcome_data (*deal_json_construct)(
    const deal_class_layout *layout, const deal_data *values,
    const uint8_t *presence, void *context);

/*
 * One emitted class plan's field-extraction seam (`C$toJson`): reads the
 * instance's field for `layout->fields[index]`, writes its closed check
 * presentation (`DEAL_PRESENT_DATUM` with the boxed persistent datum, plus the
 * carried class atom of a class datum) and its presence (0 = absent optional
 * field), and returns 1 — or returns 0 when the supplied datum is not an
 * instance of the plan's class. A class instance is opaque to every runtime
 * group, so the emitted module owns both its field slots and its nominal tag
 * and this group never inspects either.
 */
typedef int (*deal_json_read_field)(const deal_data *instance, size_t index,
                                    deal_check_input *value, uint8_t *present,
                                    void *context);

/* One class's emitted JSON plan: its declared layout and the two seams. */
typedef struct {
    const deal_class_layout *layout;      /* class atom + declaration-order fields */
    deal_json_construct construct;        /* C$fromJson instance construction     */
    deal_json_read_field read_field;      /* C$toJson field extraction            */
    void *context;                        /* the emitted module's own context     */
} deal_json_class_plan;

/* One class atom's emitted plan, or NULL when the atom has no @jsonable plan. */
typedef const deal_json_class_plan *(*deal_json_plan_lookup)(
    const char *class_atom, void *context);

/*
 * `C$fromJson`: parses `text` as one object root and decodes the plan's fields
 * in declaration order, descending into nested class plans and array positions
 * through that explicit frame stack. A duplicate
 * decoded key, an extra key, a missing required-present key, a field that fails
 * its `JSON_FROM_FIELD` descriptor check and a failed nested decode all answer
 * the language null datum: the `JSON_FROM_NULL` row's swallowed failure never
 * escapes and no partial instance is published. Missing optional fields stay
 * absent. `origin` is the call origin the field checks are rendered at.
 */
deal_outcome_data deal_json_from_class(deal_string *text,
                                       const deal_json_class_plan *plan,
                                       deal_json_plan_lookup lookup,
                                       void *lookup_context,
                                       const deal_origin *origin);

/*
 * `C$toJson`: encodes the instance's declared fields in declaration order.
 * An absent optional field is omitted, a present `null` encodes as `null`, a
 * present `undefined`-valued field is omitted (so encoded JSON need not decode
 * back), a non-finite number encodes as `null`, and every field value passes
 * its `JSON_TO_FIELD` descriptor check first. The first declaration-order
 * failure is the `JSON_TO_WALK` arm at `origin` with the walk position and the
 * failing value's typed-boundary token; a path-local container re-entry is the
 * closed cycle arm. `instance` must be a value of the plan's class.
 */
deal_outcome_string deal_json_to_class(const deal_json_class_plan *plan,
                                       const deal_data *instance,
                                       deal_json_plan_lookup lookup,
                                       void *lookup_context,
                                       const deal_origin *origin);

#endif /* DEAL_NATIVE_JSON_H */
