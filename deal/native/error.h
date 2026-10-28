/*
 * deal/native/error.h — the `error` service group.
 *
 * The Error value, the call-frame stack, the failure-arm-table transport, the
 * record/snapshot serializer and the terminal framing surface
 * (`llvm-native-runtime-value-model` R5, R9; `llvm-native-codegen-cfg-ssa` C4,
 * `CallFrameContract`).
 *
 * Failure-arm-table transport (R5): the runtime materializes every failure it
 * detects from the emitted closed arm table. The emitted project module defines
 * `deal_project_arm_table` (the extern below) and the generated start function
 * installs it through `deal_arm_table_install` before any user code runs. The
 * runtime stores the accepted table for the materialization entry and fails
 * closed on an invalid table: one deterministic stderr line and exit 2,
 * without returning to the caller. This group authors no arm text and no
 * template; the text arrives as data. It does hold the compiled closed arm
 * repertoire — the arm identities, each arm's declared parameter names, its
 * render scope and its declared expected/actual fields — because the install
 * validation must recognize an unknown arm, a missing arm and a parameter-shape
 * or render-scope mismatch without manufacturing any text. The repertoire
 * carries structure only, so the one message authority stays the emitted
 * table.
 *
 * The `deal_error` record (R5, R9) carries the required-present `code` and
 * `message` fields, the origin (`deal_origin`, stored unchanged), the optional
 * `expected` and `actual` fields, the innermost-first frame snapshot and the
 * optional cause. The instance itself is one managed object of the runtime's
 * Error layout (`llvm-native-memory-management` M1), so a root slot, a record
 * member or a carrier that holds it traces one real referent and the carried
 * Error survives its propagation path (M5); its text fields stay
 * platform-owned. An Error snapshots the shadow frame stack when it is
 * materialized; a rethrow reuses the instance and refreshes nothing. The
 * field-setting entries below write the record's own fields; the arm-driven
 * materialization entry instantiates the installed arm's own template, and the
 * host-text materialization and admission entries keep the two R5 failure
 * classes disjoint.
 */
#ifndef DEAL_NATIVE_ERROR_H
#define DEAL_NATIVE_ERROR_H

#include "deal/native/deal_rt.h"

/* -- the failure-arm-table transport (R5) -------------------------------- */

/* The supported arm-table version; any other version fails closed. */
#define DEAL_ARM_TABLE_VERSION 1U

/*
 * The closed arm identity of the DEAL-visible failure-arm set, in the
 * canonical arm order of the shared failure-projection authority. The ids are
 * the arm record's `id` field: the emitted table carries exactly this set, and
 * a record outside it, a duplicated id and a missing arm all fail the install
 * validation closed.
 */
#define DEAL_ARM_ID_TYPED_BOUNDARY_KIND             0u
#define DEAL_ARM_ID_TYPED_BOUNDARY_INVALID_UNICODE  1u
#define DEAL_ARM_ID_CLASS_IDENTITY                  2u
#define DEAL_ARM_ID_HOST_STRING_INVALID_UTF8        3u
#define DEAL_ARM_ID_HOST_STRING_SURROGATE           4u
#define DEAL_ARM_ID_ARRAY_ELEMENT_KIND              5u
#define DEAL_ARM_ID_ARRAY_READ_BOUNDS               6u
#define DEAL_ARM_ID_ARRAY_WRITE_BOUNDS              7u
#define DEAL_ARM_ID_ARRAY_DELETE_BOUNDS             8u
#define DEAL_ARM_ID_FUNCTION_SIGNATURE_MISMATCH     9u
#define DEAL_ARM_ID_HOST_PARAMETER_CELL            10u
#define DEAL_ARM_ID_HOST_SYNC_RETURN_NOTHING       11u
#define DEAL_ARM_ID_HOST_SYNC_RETURN_CELL          12u
#define DEAL_ARM_ID_ASYNC_COMPLETION_KIND          13u
#define DEAL_ARM_ID_ASYNC_SHAPE                    14u
#define DEAL_ARM_ID_HOST_LOAD_FAILED               15u
#define DEAL_ARM_ID_HOST_LOAD_RAW_SHAPE            16u
#define DEAL_ARM_ID_HOST_LOAD_NOT_A_MODULE         17u
#define DEAL_ARM_ID_HOST_LOAD_MISSING_EXPORT       18u
#define DEAL_ARM_ID_HOST_LOAD_SIGNATURE_MISMATCH   19u
#define DEAL_ARM_ID_HOST_LOAD_IDENTITY_MISMATCH    20u
#define DEAL_ARM_ID_HOST_LOAD_INVALID_METADATA     21u
#define DEAL_ARM_ID_CLASS_EXTRA_FIELD              22u
#define DEAL_ARM_ID_JSON_PARSE_ERROR               23u
#define DEAL_ARM_ID_JSON_TO_WALK                   24u
#define DEAL_ARM_ID_JSON_STRINGIFY_UNSUPPORTED     25u
#define DEAL_ARM_ID_JSON_TO_WALK_CYCLE             26u
#define DEAL_ARM_ID_SUBSTRING_BOUNDS               27u
#define DEAL_ARM_ID_SQRT_NEGATIVE                  28u

/* The number of arms the compiled closed repertoire declares. */
#define DEAL_ARM_REPERTOIRE_COUNT 29U

/* The two closed render scopes. */
#define DEAL_ARM_SCOPE_TOP_LEVEL 0U
#define DEAL_ARM_SCOPE_INNER_ONLY 1U

typedef struct {
  uint32_t id;                        /* FailureArmId ordinal */
  const char *code;                   /* 'E<number>' text */
  const char *template_text;          /* the arm's own template, unchanged */
  const char *const *parameter_names; /* declared named parameters */
  uint32_t parameter_count;
  uint8_t render_scope;               /* 0 = TOP_LEVEL, 1 = INNER_ONLY */
  uint8_t has_expected, has_actual;   /* 1 when the arm declares the field */
} deal_arm_record;
typedef struct { uint32_t version; uint32_t count; const deal_arm_record *records; } deal_arm_table;
extern const deal_arm_table deal_project_arm_table;      /* defined by the emitted project module */

/*
 * Installs the emitted table once, before any user code: 1 = accepted and
 * installed. The delivered structural rules (version, count, null text, render
 * scope, duplicate id) and this group's repertoire validation both fail closed,
 * so an unknown arm, a missing arm, a parameter-shape mismatch and a
 * render-scope or declared-field mismatch each terminate with one deterministic
 * stderr line and exit 2. Installing the accepted table again is idempotent;
 * replacing it with a different table is the same fault.
 */
int deal_arm_table_install(const deal_arm_table *table);

/* The accepted table, or NULL before the install entry accepted one. The table is
 * static emitted data whose members are text and integers, so it names no managed
 * object and contributes no collector root (M5 roots managed referents only). */
const deal_arm_table *deal_arm_table_installed(void);

/* -- materialization (R5) ------------------------------------------------ */

/* One named parameter value of one arm render. */
typedef struct {
    const char *name;
    const char *value;
} deal_arm_parameter;

/*
 * Instantiates one installed arm's own template with the arm's declared named
 * parameters and answers the message text in platform storage, released with
 * `deal_arm_text_release` (R5). Every parameter the arm declares must be
 * supplied exactly once and no parameter the arm does not declare may be
 * supplied: a missing or extra parameter is malformed parameter data and a
 * deterministic producer defect. This is also the inner-reason render, so an
 * INNER_ONLY arm is admissible here and nowhere else.
 */
char *deal_arm_message(uint32_t arm_id, const deal_arm_parameter *parameters,
                       size_t parameter_count);

/* Releases text answered by deal_arm_message (NULL is a no-op). */
void deal_arm_text_release(char *text);

/*
 * Materializes one complete failure from the installed arm table (R5): the
 * arm's own code, its own template instantiated with its declared named
 * parameters, its declared expected and actual fields at the supplied origin,
 * the innermost-first frame snapshot taken now, and the given cause.
 *
 * A missing expected field where the arm declares one, an expected field where
 * the arm declares none (and the same for actual), a parameter outside the
 * arm's declaration, an unknown arm id and a render of an INNER_ONLY arm at a
 * site are producer defects: one deterministic stderr line and exit 2, never a
 * DEAL-visible Error and never a fallback text. `cause` roots itself across the
 * allocation of the record (M5), so an E8003 leaf cause survives.
 */
deal_error *deal_error_materialize(uint32_t arm_id,
                                   const deal_arm_parameter *parameters,
                                   size_t parameter_count, const char *expected,
                                   const char *actual, const deal_origin *origin,
                                   deal_error *cause);

/* The canonical floating-point text of one arm actual (`SQRT_NEGATIVE` spells
 * its failing value): Java `Double.toHexString` parity, so `NaN`, both
 * infinities, both zeros, a subnormal's fixed-exponent spelling and every
 * other value's shortest exact `0x1.<digits>p<exp>` form. The text is written
 * into the caller's buffer, which must be at least
 * `DEAL_ARM_NUMBER_TEXT_CAPACITY` bytes. */
#define DEAL_ARM_NUMBER_TEXT_CAPACITY 32U
const char *deal_arm_number_text(double value, char *buffer, size_t capacity);

/* The three E8002 array-position arms, materialized on request (R2/R5): the
 * container group's rejected discrete position is rendered through this
 * renderer, so no second message authority exists. An id outside the three
 * bounds arms is a producer defect. */
deal_error *deal_error_array_position(uint32_t arm_id, const deal_origin *origin);

/* -- failure admission (R5, `FailureAdmission`) ------------------------- */

/*
 * Host-authored failure text (R5/H2): the host's own code, message and optional
 * expected/actual become a builtin Error with the host's text, no arm, no
 * origin, no frames and no cause. Nothing is invented and nothing is rendered
 * from the arm table.
 */
deal_error *deal_error_host_text(const char *code, const char *message,
                                 const char *expected, const char *actual);

/* One resolved failure handed to the admission entry: the host's own text or
 * the DEAL-origin Error the host relayed. */
typedef struct {
    uint32_t kind;          /* deal_failure_kind */
    deal_error *error;      /* DEAL_FAILURE_DEAL_ERROR: the resolved Error */
    const char *code;       /* DEAL_FAILURE_HOST_TEXT */
    const char *message;
    const char *expected;   /* NULL = absent */
    const char *actual;     /* NULL = absent */
} deal_failure_admission;

/*
 * Admits one resolved failure as the Error value that propagates: host text is
 * materialized once, and an already-carried `deal_error *` is returned
 * unchanged — no replacement, no added field, no refreshed origin, no new
 * frames and no new cause, so a caught, rethrown or transported Error stays one
 * object (R5). Token issuance, validation and consumption stay with the host
 * crossing; this entry sees only the resolved failure.
 */
deal_error *deal_failure_admit(const deal_failure_admission *admission);

/* -- the call-frame stack (`CallFrameContract`) -------------------------- */

/*
 * One generated function pushes its frame label on entry and pops it on every
 * exit path, so the stack holds exactly the active generated-function labels in
 * call order. The label spelling is `<modulePath>#<sourceName>@F<id>`; the
 * label text is static emitted data, never copied.
 */
void deal_call_frame_push(const char *label);
void deal_call_frame_pop(void);

/* The number of active call frames. */
uint32_t deal_call_frame_depth(void);

/* -- the Error record (R5, R9) ------------------------------------------ */

/*
 * Materializes one Error record with its required-present fields and, when
 * given, its origin stored unchanged (one-based line and column, never
 * re-based). The innermost-first frame snapshot is taken from the call-frame
 * stack at this call. `origin` is NULL for an Error that carries no origin
 * (a host-authored failure keeps the host's text with an absent origin).
 *
 * Storage: the record is one managed object of the runtime's `DEAL_LAYOUT_ERROR`
 * layout, allocated through the managed heap entry (`deal_alloc`, memory.h), so
 * its header carries the layout identity, its precise reference map covers the
 * cause and addresses never change (M1). The `memory` group's collector
 * reclaims an unreachable Error once nothing roots or references it; this group
 * allocates and shapes the record and never scans the heap. Beside the managed
 * payload the record owns platform storage — the copied code, message and origin
 * text, the optional expected and actual text, and the frames array with one
 * duplicated label per active frame — so this group registers the layout's one
 * reclamation hook (`deal_layout_release_register`, memory.h) at first use and
 * the sweep releases that storage while the payload is still intact, clearing
 * each field first so no release can happen twice. The record's cause is a
 * managed reference the collector reclaims on its own. A caller that holds an
 * Error across a further allocating call roots it in a root slot or a scoped
 * root (M5).
 */
deal_error *deal_error_create(const char *code, const char *message,
                              const deal_origin *origin);

/*
 * The optional recorded fields; each stores a copy of the given text. The copy
 * is taken before the previous value is released, so text the record already
 * owns — the accessor's own pointer, or a pointer interior to it — may be
 * passed back unchanged. A NULL replacement clears the field, which stays
 * absent rather than becoming an empty string.
 */
void deal_error_set_expected(deal_error *error, const char *expected);
void deal_error_set_actual(deal_error *error, const char *actual);

/*
 * Records the cause. The cause slot is the record's one managed-reference
 * slot, so the store runs the insertion barrier (M2); a NULL cause records no
 * cause and a nullish singleton is never shaded as heap storage. A rethrow
 * reuses the instance and leaves the stored cause unchanged.
 */
void deal_error_set_cause(deal_error *error, deal_error *cause);

/*
 * The canonical snapshot of one Error
 * (`deal.test.conformance.ErrorSnapshot.canonicalJson`): the key order code,
 * message, sourceFile, line, column, expected, actual, frames, cause; the span
 * group exactly when the Error carries an origin; the optional expected,
 * actual and cause fields exactly when present; `frames` as the canonical
 * decimal frame count; the cause as the cause Error's code; minimal RFC 8259
 * escaping with raw UTF-8 non-ASCII and `\u00XX` for the other control
 * characters; no inter-token whitespace. The bytes are platform memory,
 * NUL-terminated, and released with deal_error_snapshot_release.
 */
char *deal_error_snapshot(const deal_error *error);
void deal_error_snapshot_release(char *text);

#endif /* DEAL_NATIVE_ERROR_H */
