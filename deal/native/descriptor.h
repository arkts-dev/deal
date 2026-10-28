/*
 * deal/native/descriptor.h — the `descriptor` service group.
 *
 * Canonical descriptor atoms, the one descriptor-driven check and the class
 * construction validation of `llvm-native-runtime-value-model` R2/R4/R5.
 *
 * Grammar (R4). One parser reads exactly the canonical public descriptor
 * grammar of `docs/spec-v1.5.md`:
 *
 *     descriptor := primitive | error | class | array | nullish | function
 *     primitive  := "null" | "undefined" | "boolean" | "number" | "string"
 *                 | "record" | "unknown"
 *     error      := "Error"
 *     class      := "@" modulePath "/" ClassName
 *     array      := "[" descriptor "]"
 *     nullish    := ("?" | "!" | "?!") descriptor | "null|undefined"
 *     function   := "async"? "(" (descriptor ("," descriptor)*)? ")" "->"
 *                   (descriptor | "void")
 *
 * `void` is result-only: it is rejected in a data, array, element and
 * parameter position. A nullish prefix needs a non-nullish, non-unknown base
 * and does not nest, `?null`, `!undefined` and `null|undefined` carry the
 * canonical nullish forms of the shared type system, and source `Promise<R>`
 * notation is an async function signature and never a storable descriptor.
 * The emitted module passes descriptor text as static data and this group
 * interns it into atoms; a text outside the grammar is a producer defect
 * reported deterministically (`deal_platform_fault`), never a DEAL Error and
 * never a silently accepted descriptor.
 *
 * Values (R2/R4). Two distinct presentations reach a check: the persistent
 * tagged datum `deal_data` of an `unknown`, record, dynamic-apply or decoded
 * position (every data kind included), and the closed non-data presentations
 * `missing`, `nothing`, `async-operation` and `invalid-unicode`. The datum's
 * tag classifies it into the closed actual-kind set (`deal_value_kind`) and no
 * second classification exists; a class datum carries its canonical class atom
 * and a function datum its canonical signature text, because the emitted
 * module owns those representations and the runtime never derives a target
 * class name of its own.
 *
 * Failures (R5). `deal_check_value` publishes exactly the interface contract
 * of the closed failure-arm table: the arm's own code, its own template
 * instantiated with the arm's declared named parameters, the arm's declared
 * expected/actual projection and the supplied origin. The `error` group owns
 * the arm table and the one renderer (`deal_error_materialize`), so this group
 * authors no template, no message suffix and no second projection authority:
 * it selects the arm the boundary declares and supplies that arm's projection
 * values.
 *
 * Arrays (R4). An array's persistent element descriptor decides compatibility:
 * the check compares it against the expected element descriptor, so an empty
 * array and an array whose elements happen to match another element type keep
 * the type they were built with. The elements themselves were admitted at their
 * store boundary (the array store, the JSON decode or the host crossing that
 * built them), so the check never re-scans present elements and never infers a
 * descriptor from them.
 *
 * Class construction (`ConstructClass`, R2). Supplied fields are validated
 * once, in the recorded declaration order, at their own recorded origins:
 * every required field is supplied exactly once, omitted optionals stay
 * absent, an unknown supplied field is the arm's E8007 rejection, and the
 * instance's tag is published only after every field check succeeded, so no
 * partial instance exists and every earlier expression effect remains.
 */
#ifndef DEAL_NATIVE_DESCRIPTOR_H
#define DEAL_NATIVE_DESCRIPTOR_H

#include "deal/native/deal_rt.h"

/* An interned canonical descriptor atom; the layout is private to the group. */
typedef struct deal_descriptor deal_descriptor;

/* The closed descriptor kinds of the canonical grammar (R4). `void` is the
 * result-only kind: it admits exactly `undefined` and exists in no data,
 * array, element or parameter position. */
typedef enum {
    DEAL_DESCRIPTOR_NULL = 0,
    DEAL_DESCRIPTOR_UNDEFINED,
    DEAL_DESCRIPTOR_VOID,
    DEAL_DESCRIPTOR_BOOLEAN,
    DEAL_DESCRIPTOR_NUMBER,
    DEAL_DESCRIPTOR_STRING,
    DEAL_DESCRIPTOR_RECORD,
    DEAL_DESCRIPTOR_UNKNOWN,
    DEAL_DESCRIPTOR_CLASS,
    DEAL_DESCRIPTOR_ARRAY,
    DEAL_DESCRIPTOR_NULLISH,
    DEAL_DESCRIPTOR_FUNCTION
} deal_descriptor_kind;

/* Interns canonical descriptor text and answers its one atom: identical text
 * answers the identical atom for the process lifetime, so atom identity is
 * descriptor identity and a persistent element descriptor is one reference.
 * Malformed or degenerate text is a producer defect reported deterministically
 * (it never becomes a DEAL Error). */
deal_descriptor *deal_descriptor_intern(const char *descriptor_text);

/* The atom's canonical text, unchanged from the interned text. */
const char *deal_descriptor_text(deal_descriptor *atom);

/* The atom's closed kind. */
deal_descriptor_kind deal_descriptor_type(deal_descriptor *atom);

/* The element atom of an array descriptor `[T]`; a non-array atom faults. */
deal_descriptor *deal_descriptor_element(deal_descriptor *atom);

/* The base atom of a nullish descriptor (`?T`, `!T`, `?!T`, `null|undefined`);
 * a non-nullish atom faults. */
deal_descriptor *deal_descriptor_inner(deal_descriptor *atom);

/* 1 when the nullish descriptor admits `null`, 0 otherwise. */
int deal_descriptor_admits_null(deal_descriptor *atom);

/* 1 when the nullish descriptor admits `undefined`, 0 otherwise. */
int deal_descriptor_admits_undefined(deal_descriptor *atom);

/* The carried canonical class atom of a class descriptor: `Error` for the
 * builtin class, `@modulePath/ClassName` otherwise. */
const char *deal_descriptor_class_atom(deal_descriptor *atom);

/* The closed `{kind}` vocabulary of one atom (the typed-boundary kind text):
 * a class descriptor spells `class instance`, a record descriptor `table`,
 * `void` projects `undefined`, and a nullish descriptor its base's text. */
const char *deal_descriptor_kind_text(deal_descriptor *atom);

/* The closed expected token of one atom (the typed-boundary kind token): the
 * kind text with a class spelled `class`. */
const char *deal_descriptor_kind_token(deal_descriptor *atom);

/* A stable reference to one atom for storage beside a managed object (the
 * `container` group records an array's persistent element descriptor). 0 is
 * the absent reference; an atom always has a non-zero reference and an
 * unknown reference is an invariant fault. */
uint32_t deal_descriptor_reference(deal_descriptor *atom);
deal_descriptor *deal_descriptor_of_reference(uint32_t reference);

/* The recorded context of one checked boundary: the op's own origin
 * (`DiagnosticOriginProjection`, never re-based) and, for a host parameter
 * cell, the one-based parameter index the arm's `{index}` names. */
typedef struct {
    const deal_origin *origin;
    int32_t parameter_index;
} deal_check_context;

/* R4: the one descriptor-driven check. On success `error` is NULL and the
 * checked value passes through unchanged; on failure `error` is the arm's
 * materialized Error at the context's origin. A missing descriptor, a missing
 * origin, a function descriptor at a non-function boundary or a boundary
 * outside the closed set is a producer defect, reported deterministically.
 * The host cell arms declare `CELL_DESCRIPTOR`, so their expected field — and
 * the `{expected}` parameter of the host-return nothing arm — carries the
 * boundary's own declared descriptor text, never the recursed base
 * descriptor of a nullish cell (R5). */
deal_outcome deal_check_value(deal_descriptor *expected, deal_check_input input,
                              uint32_t boundary_kind,
                              const deal_check_context *context);

/*
 * The typed-boundary token of one checked presentation: the closed
 * arm-actual vocabulary the check itself renders — `undefined` for the absent
 * marker and the undefined datum, `null`, `boolean`, `number`, `string`,
 * `invalid-unicode`, `table`, `array`, `function`, `async-operation`,
 * `nothing`, or a class datum's carried canonical atom (the `Error` atom for
 * the builtin instance). It is published so a consumer that reports a position
 * of its own — the `json` group's walk arm — projects exactly this token and
 * never derives a second classification. A class datum without its carried atom
 * or a function datum without its carried signature is a producer defect.
 */
const char *deal_check_token(const deal_check_input *input);

/* The scalar boundary helpers (R4): admit one persistent datum at one declared
 * scalar position and project its native payload through the same arm tuple as
 * `deal_check_value`. */
deal_outcome_number deal_check_number(const deal_data *datum,
                                      const deal_check_context *context);
deal_outcome_boolean deal_check_boolean(const deal_data *datum,
                                        const deal_check_context *context);
deal_outcome_string deal_check_string(const deal_data *datum,
                                      const deal_check_context *context);

/* -- class construction (R2, `ConstructClass`) --------------------------- */

/* One supplied literal field, in literal (provided-source) order, with the
 * origin of the supplied expression's own boundary check. */
typedef struct {
    const char *name;
    deal_check_input value;
    deal_origin origin;
} deal_class_supplied;

/* Validates one construction request before anything is published: an unknown
 * supplied field is the E8007 arm at `origin`, then every declared field's
 * supplied value is checked at its own origin in declaration order. On success
 * `*out_class_atom` is the tag the instance carries and every required field
 * was supplied; `error` is NULL. `out_class_atom` is written on success only,
 * so an instance is tagged and published only after successful validation. A
 * duplicated supplied field or a missing required field is a frontend error
 * and therefore a producer defect here, reported deterministically. */
deal_outcome deal_class_construct(const deal_class_layout *layout,
                                  const deal_class_supplied *supplied,
                                  size_t supplied_count,
                                  const deal_origin *origin,
                                  const char **out_class_atom);

#endif /* DEAL_NATIVE_DESCRIPTOR_H */
