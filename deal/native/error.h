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
 * runtime stores the accepted table for the later materialization entry and
 * fails closed on a structurally invalid table: one deterministic stderr line
 * and exit 2, without returning to the caller. This group authors no arm text,
 * no template, no parameter projection and no repertoire rule; the text arrives
 * as data.
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
 * materialization entry that instantiates a template arrives with the
 * runtime-boundaries-errors child.
 */
#ifndef DEAL_NATIVE_ERROR_H
#define DEAL_NATIVE_ERROR_H

#include "deal/native/deal_rt.h"

/* -- the failure-arm-table transport (R5) -------------------------------- */

/* The supported arm-table version; any other version fails closed. */
#define DEAL_ARM_TABLE_VERSION 1U

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
int deal_arm_table_install(const deal_arm_table *table); /* 1 = accepted and installed */

/* The accepted table, or NULL before the install entry accepted one. The
 * collector registers it among its static roots. */
const deal_arm_table *deal_arm_table_installed(void);

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
 * cause and addresses never change (M1). Reclaiming an unreachable Error is the
 * collector child's; this group allocates and shapes the record and never
 * scans the heap. The record's text fields stay platform memory, and a caller
 * that holds an Error across a further allocating call roots it in a root slot
 * or a scoped root (M5).
 */
deal_error *deal_error_create(const char *code, const char *message,
                              const deal_origin *origin);

/* The optional recorded fields; each stores a copy of the given text. */
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
