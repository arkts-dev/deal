/*
 * deal/native/json.h — the `json` service group.
 *
 * One JSON service group implements the shared JSON rules
 * (`llvm-native-runtime-value-model` R6): strict grammar scanning with one-based
 * UTF-8 byte positions, duplicate decoded-key rejection per object, JSON
 * numeric tokens parsed directly to `number` (IEEE 754 binary64, round to
 * nearest, ties to even, signed zero and numeric overflow/underflow preserved),
 * lossless UTF-16 strings (including lone units), malformed-UTF-8 rejection, stack-safe
 * traversal without a fixed nesting cap, path-local cycle detection on encode,
 * and non-finite numbers encoded as null.
 *
 * Decoded graphs are persistent tagged data (`deal_data`), never crossing
 * handles: a decoded JSON object is a record, a decoded array records the
 * `unknown` element descriptor before contextual reconstruction, and a decoded
 * `null` token is the language null
 * datum, so JSON decoding never creates stored `undefined`.
 *
 * Failures materialize through the installed closed arm table. Syntax and
 * duplicate keys precede contextual reconstruction; extra fields precede
 * declaration-ordered field checks. Reconstruction throws without publishing
 * partial graphs. Builtin stringify validates each reached declared position
 * through deal_check_value at the operation origin before omission/substitution.
 * Typed array positions compose E8003 with the original leaf cause; unsupported
 * presentations and path-local cycles retain their separate encoding arms.
 *
 * Emitted exact class plans supply declaration-order layouts and registered
 * construction/read seams, never helper functions or comment-selected authority.
 * Invocation-local explicit stacks own reconstruction and encoding traversal.
 * Decoded typed arrays record their declared element descriptor beside the
 * runtime-owned element-storage layout consumed by emitted element access.
 * Null/undefined elements occupy reference slots containing distinct runtime
 * singletons; null|undefined admits undefined through the descriptor's base.
 */
#ifndef DEAL_NATIVE_JSON_H
#define DEAL_NATIVE_JSON_H

#include "deal/native/deal_rt.h"

/* -- internal scanning and builtin encoding entries --------------------- */

/*
 * Parses one JSON text into a value graph and answers it as persistent tagged
 * data. A syntax defect, a duplicate decoded member key or an encoding defect
 * is the installed `JSON_PARSE_ERROR` arm at `origin` with the defect's
 * one-based UTF-8 byte offset and its closed reason text; the top-level value
 * may be any JSON value. Contextual reconstruction is a separate entry.
 */
/* ECMAScript radix-ten number text, separate from JSON presentation. */
deal_string *deal_number_to_string(double value);

deal_outcome_data deal_json_parse(deal_string *text, const deal_origin *origin);

/*
 * The builtin JSON.stringify encoder. Answers the encoded text
 * as a string datum, or the undefined datum when the root value carries no
 * JSON representation (top-level `undefined` or a function value). Encoding
 * follows spec v1.6: non-finite numbers become `null`, finite output numbers
 * roundtrip to the same binary64 value with negative zero as `0`, `null` is
 * preserved, `undefined`/function-valued object members are omitted while such
 * array elements encode as `null`, a record's own keys encode array-index keys
 * ascending first and then in creation order, and a path-local container
 * re-entry is the walk family's cycle arm; shared acyclic children encode
 * twice. Installed class plans provide declared fields; Error emits code/message.
 * Unsupported presentations use `JSON_STRINGIFY_UNSUPPORTED` with their
 * carrier-kind token. Neither arm exposes partial output.
 */
deal_outcome_data deal_json_stringify(deal_data value,
                                      const deal_origin *origin);

/* -- declaration plans for contextual reconstruction and encoding -------- */

/*
 * One emitted class plan's reconstruction seam: builds one
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
 * One emitted class plan's field-extraction seam: reads the
 * instance's field for `layout->fields[index]`, writes its closed check
 * presentation (`DEAL_PRESENT_DATUM` with the boxed persistent datum, plus the
 * carried class atom of a class datum) and its presence (0 = absent optional
 * field), and returns 1 — or returns 0 when the supplied datum is not an
 * instance of the plan's class. The nominal tag alone identifies the class, so
 * an index outside the declared field list answers 1 with presence 0 ("this is
 * my class, no such field") and a class that declares no field is identifiable
 * without one. A NULL value output requests presence only: do not load or
 * classify the stored field value. A class instance is opaque to every runtime
 * group, so the emitted module owns both its field slots and its nominal tag
 * and this group never inspects either.
 */
typedef int (*deal_json_read_field)(const deal_data *instance, size_t index,
                                    deal_check_input *value, uint8_t *present,
                                    void *context);

/* One class's emitted JSON plan: its declared layout and the two seams. */
typedef struct {
    const deal_class_layout *layout;      /* class atom + declaration-order fields */
    deal_json_construct construct;        /* contextual instance reconstruction  */
    deal_json_read_field read_field;      /* declared-field extraction            */
    void *context;                        /* the emitted module's own context     */
} deal_json_class_plan;

/* One class atom's emitted plan, or NULL when the atom has no plan. */
typedef const deal_json_class_plan *(*deal_json_plan_lookup)(
    const char *class_atom, void *context);

/* Install the project's ordinary class plans for generic JSON traversal. */
void deal_json_plans_install(deal_json_plan_lookup lookup, void *context);

/* Fresh present-own-key snapshot; no field value admission or graph traversal. */
deal_array *deal_object_keys(const deal_data *value);

/* Target-directed JSON reconstruction. Errors retain the parse call origin;
 * no partially decoded result is published. Plans are emitted declarations. */
deal_outcome_data deal_json_decode(deal_string *text, deal_descriptor *target,
                                   deal_json_plan_lookup lookup, void *context,
                                   const deal_origin *origin);

#endif /* DEAL_NATIVE_JSON_H */
