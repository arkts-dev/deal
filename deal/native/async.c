/*
 * deal/native/async.c — the `async` service group implementation (H5).
 *
 * The open-operation table, the runtime-allocated tokens, the C7 blocking
 * lowering entries, the completion intake of the four H5 entries, the
 * single delivery path, the service-hook pump and the async export invocation
 * surface of H6.
 *
 * Tokens are 64-bit values from one process-wide monotonic counter that never
 * issues zero and never reuses or recycles a value, so a token names exactly one
 * live operation for the process lifetime: the table entry is removed when the
 * consuming `AWAIT` takes the completion or when the start abandons the
 * operation, and every later completion for that token is unattributable rather
 * than a delivery to a later operation.
 *
 * Rooting (M5, `OpenOperationRecord`, `QueuedCompletionLifetime`): the
 * open-operation table is a registered root source that visits each live
 * record and, through it, the delivered completion. A queued platform frame
 * holds copied bytes and is never scanned as an object, so nothing is rooted
 * before delivery; the delivery store runs the insertion barrier, and a
 * materialized carrier is created and stored before its crossing-equivalent
 * scope is released.
 *
 * Threading (M3): only the one mutator thread runs `ASYNC_START`, `AWAIT`, the
 * pump, service hooks, carrier materialization and the two managed completion
 * entries. Any thread may post a copied scalar/text/error record, which touches
 * no managed state and no table.
 */
#include "deal/native/async.h"

#include "deal/native/descriptor.h"
#include "deal/native/error.h"
#include "deal/native/host.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

#include <string.h>

/* =========================================================================
 * The open-operation table and the operation record (H5, M5)
 * ========================================================================= */

/*
 * One live async operation. The record is a managed object of its own runtime
 * layout, so the open-operation table roots it and its delivered completion,
 * and the layout's single precise reference slot is the completion datum's
 * managed payload (a scalar datum, a nullish datum or the carried Error leaves
 * it in the tag-aware form the map expects).
 */
typedef struct deal_async_op {
    deal_async_token token;
    uint32_t completed;      /* the exactly-once delivery flag */
    uint32_t is_error;       /* the completion is the carried Error */
    uint32_t presentation;   /* deal_presentation: DATUM or INVALID_UNICODE */
    uint32_t unicode_defect; /* deal_unicode_defect for an INVALID_UNICODE slot */
    deal_data completion;
} deal_async_op;

static const uint32_t deal_async_operation_reference_offsets[] = {
    (uint32_t)offsetof(deal_async_op, completion)
        + (uint32_t)offsetof(deal_data, reference)
};

static const deal_layout deal_async_operation_layout = {
    DEAL_LAYOUT_ASYNC_OPERATION, 0u, sizeof(deal_async_op),
    deal_async_operation_reference_offsets, 1u
};

/* One open-operation table entry: exactly one record per live token. A retired
 * entry keeps its slot for reuse; its token is never issued again. */
typedef struct {
    deal_async_token token;
    deal_async_op *record;
    int live;
} deal_async_entry;

static deal_async_entry *deal_async_operations;
static size_t deal_async_operation_count;
static size_t deal_async_operation_capacity;
static uint64_t deal_async_token_counter;
static int deal_async_registered;
static int deal_async_pumping;

/* The table is a root source (M5): every live record and the completion it
 * holds are visited, so a delivered value survives a collection between
 * delivery and the consuming `AWAIT`. */
static void deal_async_operation_scan(deal_reference_visit visit,
                                      void *visit_context, void *context) {
    (void)context;
    for (size_t index = 0; index < deal_async_operation_count; index += 1u) {
        if (deal_async_operations[index].live == 0) {
            continue;
        }
        deal_async_op *record = deal_async_operations[index].record;
        visit(record, visit_context);
        void *referent = deal_data_referent(&record->completion);
        if (referent != NULL) {
            visit(referent, visit_context);
        }
    }
}

static void deal_async_table_ensure(void) {
    if (deal_async_registered != 0) {
        return;
    }
    deal_async_registered = 1;
    deal_root_source_register(deal_async_operation_scan, NULL);
}

static void deal_async_table_grow(void) {
    if (deal_async_operation_count < deal_async_operation_capacity) {
        return;
    }
    size_t capacity = deal_async_operation_capacity == 0u
        ? 8u : deal_async_operation_capacity * 2u;
    deal_async_entry *grown = deal_platform_allocate(
        deal_platform_checked_multiply(capacity, sizeof(*grown)));
    if (grown == NULL) {
        deal_platform_fault("async: open-operation table allocation failed");
    }
    memset(grown, 0, capacity * sizeof(*grown));
    if (deal_async_operations != NULL) {
        memcpy(grown, deal_async_operations,
            deal_async_operation_count * sizeof(*grown));
        deal_platform_release(deal_async_operations);
    }
    deal_async_operations = grown;
    deal_async_operation_capacity = capacity;
}

/* The live entry of one token, or NULL. */
static deal_async_entry *deal_async_find(deal_async_token token) {
    if (token == 0) {
        return NULL;
    }
    for (size_t index = 0; index < deal_async_operation_count; index += 1u) {
        if (deal_async_operations[index].live != 0
                && deal_async_operations[index].token == token) {
            return &deal_async_operations[index];
        }
    }
    return NULL;
}

/* The live entry of one record, or NULL. */
static deal_async_entry *deal_async_find_record(deal_async_op *record) {
    for (size_t index = 0; index < deal_async_operation_count; index += 1u) {
        if (deal_async_operations[index].live != 0
                && deal_async_operations[index].record == record) {
            return &deal_async_operations[index];
        }
    }
    return NULL;
}

/* The next token of the one process-wide monotonic sequence (never 0, never
 * reused). */
static deal_async_token deal_async_token_next(void) {
    deal_async_token_counter += 1u;
    if (deal_async_token_counter == 0u) {
        deal_async_token_counter = 1u;
    }
    return (deal_async_token)deal_async_token_counter;
}

/* Registers one record as the live operation of one token. */
static void deal_async_table_register(deal_async_op *record,
                                      deal_async_token token) {
    deal_async_table_ensure();
    deal_async_table_grow();
    for (size_t index = 0; index < deal_async_operation_count; index += 1u) {
        if (deal_async_operations[index].live == 0) {
            deal_async_operations[index].token = token;
            deal_async_operations[index].record = record;
            deal_async_operations[index].live = 1;
            return;
        }
    }
    deal_async_entry *entry = &deal_async_operations[deal_async_operation_count];
    entry->token = token;
    entry->record = record;
    entry->live = 1;
    deal_async_operation_count += 1u;
}

/* Removes one record's entry: its token is retired forever (H5). */
static void deal_async_table_retire(deal_async_op *record) {
    deal_async_entry *entry = deal_async_find_record(record);
    if (entry == NULL) {
        deal_platform_fault("async: retirement of a record outside the table");
    }
    entry->live = 0;
    entry->record = NULL;
    entry->token = 0;
}

/* One new operation record with its operation slot still empty. */
static deal_async_op *deal_async_record_new(deal_async_token token) {
    deal_layout_define(&deal_async_operation_layout);
    deal_async_op *record = deal_alloc(DEAL_LAYOUT_ASYNC_OPERATION,
        sizeof(*record));
    record->token = token;
    record->completed = 0u;
    record->is_error = 0u;
    record->presentation = DEAL_PRESENT_DATUM;
    record->unicode_defect = DEAL_UNICODE_MALFORMED;
    record->completion = deal_data_of_undefined();
    return record;
}

/* Stores one completion into the record through the insertion barrier and
 * marks it completed exactly once (M2, H5). The datum is held across the store,
 * because a delivered managed payload may be the first reference the heap
 * learns of it. */
static void deal_async_record_complete(deal_async_op *record,
                                       const deal_data *datum, int is_error,
                                       uint32_t presentation,
                                       uint32_t unicode_defect) {
    deal_root_scope_push();
    (void)deal_root_scope_hold_data(datum);
    deal_store_barrier_data(record, datum);
    record->completion = *datum;
    record->is_error = is_error != 0 ? 1u : 0u;
    record->presentation = presentation;
    record->unicode_defect = unicode_defect;
    record->completed = 1u;
    deal_root_scope_pop();
}

/* =========================================================================
 * ASYNC_START and AWAIT (C7)
 * ========================================================================= */

deal_outcome_reference deal_async_start(deal_fn *callee,
                                        const deal_check_input *arguments,
                                        uint32_t argument_count,
                                        const deal_origin *origin) {
    deal_outcome_reference outcome;
    outcome.error = NULL;
    outcome.value = NULL;
    if (callee == NULL || origin == NULL) {
        deal_platform_fault("async: malformed async start");
    }
    if (arguments == NULL && argument_count != 0u) {
        deal_platform_fault("async: an async start without its arguments");
    }
    if (deal_fn_is_async(callee) == 0) {
        deal_platform_fault("async: an async start of a synchronous callee");
    }
    if (deal_platform_on_mutator_thread() == 0) {
        /* Only the mutator starts an operation: it allocates the record, runs
         * DEAL code and owns every later completion path (M3, H5). */
        deal_platform_fault("async: an async start off the mutator thread");
    }
    deal_async_token token = deal_async_token_next();
    /* The callee is this helper's own managed intermediate across the record
     * allocation and the invocation that follows (M5): the emitted module roots
     * its own temporaries, and this scope roots the carrier the start consumes. */
    deal_root_scope_push();
    uint32_t held = deal_root_scope_hold(callee);
    deal_async_op *record = deal_async_record_new(token);
    deal_async_table_register(record, token);
    if (deal_host_is_async_function_value(callee) != 0) {
        /* The declared host async export: the crossing calls the host entry
         * with the token and the checked argument carriers, and the operation
         * stays pending until a completion is delivered (H5). */
        deal_error *failure = deal_host_invoke_async_value(callee, token,
            arguments, argument_count, origin);
        if (failure != NULL) {
            /* The start abandons the operation: its entry is removed and the
             * token retired, so a late completion is unattributable. */
            deal_async_table_retire(record);
            deal_root_scope_release(held);
            deal_root_scope_pop();
            outcome.error = failure;
            return outcome;
        }
    } else {
        /* A DEAL function value: its body runs to completion here and the
         * recorded completion is its result or the carried Error (C7). */
        deal_outcome_data applied = deal_fn_apply(callee, arguments,
            argument_count, origin);
        if (applied.error != NULL) {
            deal_data datum = deal_data_of_reference(DEAL_DATA_ERROR,
                applied.error);
            deal_async_record_complete(record, &datum, 1,
                DEAL_PRESENT_DATUM, DEAL_UNICODE_MALFORMED);
        } else {
            deal_async_record_complete(record, &applied.value, 0,
                DEAL_PRESENT_DATUM, DEAL_UNICODE_MALFORMED);
        }
    }
    deal_root_scope_release(held);
    deal_root_scope_pop();
    outcome.value = record;
    return outcome;
}

/* Takes one queued frame's copied record and delivers it: the token is resolved
 * against the open-operation table, the payload is materialized, the completion
 * is stored through the barrier and the record is marked completed exactly
 * once. A frame that names no live pending operation, a duplicate delivery and
 * a foreign frame are the deterministic protocol failures of H5. */
static void deal_async_deliver(deal_completion_frame *frame);

/* The pump (H5): service hooks in registration order, every queued frame, then
 * one bounded platform wait, until the operation is completed. The pump is not
 * reentrant: a second `AWAIT` that would pump while this thread is already
 * inside the pump is the host protocol failure, because blocking lowering
 * cannot suspend the inner activation. */
static void deal_async_pump(deal_async_op *record) {
    if (deal_async_pumping != 0) {
        deal_host_protocol_fault("a reentrant async pump");
    }
    deal_async_pumping = 1;
    while (record->completed == 0u) {
        /* One pump cycle runs inside the pump's crossing-equivalent scope: a
         * service hook completes with a crossing carrier, re-enters DEAL code
         * and delivers a relayed failure exactly as it does inside its own
         * export call, and every carrier the cycle issued is released when the
         * cycle ends (H5, M5). */
        deal_host_scope_push(NULL);
        for (size_t index = 0; index < deal_host_registry_count; index += 1u) {
            const deal_host_module *module = deal_host_registry[index].module;
            if (module == NULL || module->service == NULL) {
                continue;
            }
            module->service();
            if (record->completed != 0u) {
                break;
            }
        }
        if (record->completed == 0u) {
            deal_completion_frame *frame = deal_platform_take();
            while (frame != NULL) {
                deal_async_deliver(frame);
                deal_platform_frame_free(frame);
                frame = deal_platform_take();
            }
        }
        deal_host_scope_pop();
        if (record->completed != 0u) {
            break;
        }
        deal_platform_wait_ns(DEAL_PUMP_SLICE_NS);
    }
    deal_async_pumping = 0;
}

deal_outcome_data deal_async_await(void *operation,
                                   const char *completion_descriptor,
                                   const deal_origin *origin) {
    deal_outcome_data outcome;
    outcome.error = NULL;
    outcome.value = deal_data_of_undefined();
    if (operation == NULL || completion_descriptor == NULL || origin == NULL) {
        deal_platform_fault("async: malformed await");
    }
    deal_async_op *record = operation;
    if (deal_object_layout(record) != DEAL_LAYOUT_ASYNC_OPERATION) {
        deal_platform_fault("async: an await of a non-operation handle");
    }
    if (deal_async_find_record(record) == NULL) {
        deal_platform_fault("async: an await of a retired operation");
    }
    if (record->completed == 0u) {
        if (deal_platform_on_mutator_thread() == 0) {
            deal_platform_fault("async: the pump ran off the waiting thread");
        }
        deal_async_pump(record);
    }
    if (record->is_error != 0u) {
        /* The carried Error is raised at the await expression unchanged (C7). */
        if (record->completion.tag != DEAL_DATA_ERROR
                || record->completion.reference == NULL) {
            deal_platform_fault("async: an error completion without its Error");
        }
        outcome.error = record->completion.reference;
        deal_async_table_retire(record);
        return outcome;
    }
    deal_descriptor *declared = deal_descriptor_intern_result(
        completion_descriptor);
    deal_check_input checked;
    memset(&checked, 0, sizeof(checked));
    if (record->presentation == DEAL_PRESENT_INVALID_UNICODE) {
        checked.presentation = DEAL_PRESENT_INVALID_UNICODE;
        checked.unicode_defect = record->unicode_defect;
    } else {
        deal_host_present_datum(record->completion, &checked);
    }
    deal_check_context context;
    context.origin = origin;
    context.parameter_index = 0;
    deal_outcome admitted = deal_check_value(declared, checked,
        DEAL_BOUNDARY_ASYNC_COMPLETION, &context);
    if (admitted.error != NULL) {
        deal_async_table_retire(record);
        outcome.error = admitted.error;
        return outcome;
    }
    outcome.value = record->completion;
    deal_async_table_retire(record);
    return outcome;
}

/* =========================================================================
 * The completion frame copy-in (R1, H5 `deal_async_post`)
 * ========================================================================= */

/* One copied text region of the frame payload: its byte offset from the payload
 * base and its length, with the copied bytes NUL-terminated. */
typedef struct {
    size_t offset;
    size_t length;
} deal_async_text;

/* The closed copied record at the head of one frame payload: no carrier, no
 * handle, no managed pointer and no DEAL-origin failure token. */
typedef struct {
    deal_async_token token;
    uint32_t kind;         /* deal_host_completion_kind */
    uint32_t has_expected;
    uint32_t has_actual;
    int boolean;
    double number;
    deal_async_text string;
    deal_async_text code;
    deal_async_text message;
    deal_async_text expected;
    deal_async_text actual;
} deal_async_frame_record;

/* Places one NUL-terminated copy of `text` in the reserved payload at `cursor`
 * and answers its span; the caller reserved the whole payload before, so no
 * reservation or append happens here. */
static deal_async_text deal_async_frame_place(unsigned char *base,
                                              size_t *cursor,
                                              const char *text) {
    deal_async_text span;
    span.length = strlen(text);
    span.offset = *cursor;
    memcpy(base + span.offset, text, span.length);
    base[span.offset + span.length] = 0;
    *cursor += span.length + 1u;
    return span;
}

deal_status deal_async_post(deal_async_token token,
                            const deal_host_completion *completion) {
    if (completion == NULL) {
        deal_platform_fault("async: a completion post without its record");
    }
    if (token == 0) {
        return DEAL_STATUS_PROTOCOL;
    }
    uint32_t kind = (uint32_t)completion->kind;
    if (kind > (uint32_t)DEAL_COMPLETION_UNDEFINED) {
        return DEAL_STATUS_PROTOCOL;
    }
    if (kind == (uint32_t)DEAL_COMPLETION_STRING
            && completion->as.string.length != 0u
            && completion->as.string.data == NULL) {
        return DEAL_STATUS_PROTOCOL;
    }
    int has_expected = 0;
    int has_actual = 0;
    size_t text_bytes = 0u;
    if (kind == (uint32_t)DEAL_COMPLETION_ERROR) {
        if (completion->as.error.code == NULL
                || completion->as.error.message == NULL) {
            return DEAL_STATUS_PROTOCOL;
        }
        has_expected = completion->as.error.expected != NULL ? 1 : 0;
        has_actual = completion->as.error.actual != NULL ? 1 : 0;
        text_bytes = deal_platform_checked_add(
            strlen(completion->as.error.code), 1u);
        text_bytes = deal_platform_checked_add(text_bytes,
            deal_platform_checked_add(
                strlen(completion->as.error.message), 1u));
        if (has_expected != 0) {
            text_bytes = deal_platform_checked_add(text_bytes,
                deal_platform_checked_add(
                    strlen(completion->as.error.expected), 1u));
        }
        if (has_actual != 0) {
            text_bytes = deal_platform_checked_add(text_bytes,
                deal_platform_checked_add(
                    strlen(completion->as.error.actual), 1u));
        }
    } else if (kind == (uint32_t)DEAL_COMPLETION_STRING) {
        text_bytes = deal_platform_checked_add(completion->as.string.length,
            1u);
    }
    deal_completion_frame *frame = deal_platform_frame_alloc();
    unsigned char *base = deal_platform_frame_payload(frame,
        deal_platform_checked_add(sizeof(deal_async_frame_record),
            text_bytes));
    deal_async_frame_record record;
    memset(&record, 0, sizeof(record));
    record.token = token;
    record.kind = kind;
    record.boolean = completion->as.boolean;
    record.number = completion->as.number;
    record.has_expected = (uint32_t)has_expected;
    record.has_actual = (uint32_t)has_actual;
    size_t cursor = sizeof(record);
    if (kind == (uint32_t)DEAL_COMPLETION_ERROR) {
        record.code = deal_async_frame_place(base, &cursor,
            completion->as.error.code);
        record.message = deal_async_frame_place(base, &cursor,
            completion->as.error.message);
        if (has_expected != 0) {
            record.expected = deal_async_frame_place(base, &cursor,
                completion->as.error.expected);
        }
        if (has_actual != 0) {
            record.actual = deal_async_frame_place(base, &cursor,
                completion->as.error.actual);
        }
    } else if (kind == (uint32_t)DEAL_COMPLETION_STRING) {
        record.string.offset = cursor;
        record.string.length = completion->as.string.length;
        if (completion->as.string.length != 0u) {
            memcpy(base + cursor, completion->as.string.data,
                completion->as.string.length);
        }
        base[cursor + record.string.length] = 0;
        cursor += record.string.length + 1u;
    }
    memcpy(base, &record, sizeof(record));
    deal_platform_post(frame);
    return DEAL_OK;
}

/* The copied text at one span of one taken frame payload. */
static const char *deal_async_frame_span(const unsigned char *base,
                                         deal_async_text span) {
    return (const char *)(base + span.offset);
}

static void deal_async_deliver(deal_completion_frame *frame) {
    const unsigned char *base = deal_platform_frame_bytes(frame);
    if (base == NULL
            || deal_platform_frame_payload_size(frame)
                < sizeof(deal_async_frame_record)) {
        deal_host_protocol_fault("a completion frame without its copied"
                                 " record");
    }
    deal_async_frame_record copy;
    memcpy(&copy, base, sizeof(copy));
    deal_async_entry *entry = deal_async_find(copy.token);
    if (entry == NULL) {
        /* The frame is already in flight and cannot be returned to its sender:
         * an unattributable completion is the deterministic protocol failure. */
        deal_host_protocol_fault("a completion names no live async operation");
    }
    deal_async_op *record = entry->record;
    if (record->completed != 0u) {
        deal_host_protocol_fault("a duplicate completion for one async"
                                 " operation");
    }
    switch ((deal_host_completion_kind)copy.kind) {
    case DEAL_COMPLETION_NUMBER: {
        /* The binary64 value is stored exactly: NaN, infinities and signed zero
         * are values, never a rounded or normalized carrier. */
        deal_data datum = deal_data_of_number(copy.number);
        deal_async_record_complete(record, &datum, 0, DEAL_PRESENT_DATUM,
            DEAL_UNICODE_MALFORMED);
        return;
    }
    case DEAL_COMPLETION_BOOLEAN: {
        deal_data datum = deal_data_of_boolean(copy.boolean);
        deal_async_record_complete(record, &datum, 0, DEAL_PRESENT_DATUM,
            DEAL_UNICODE_MALFORMED);
        return;
    }
    case DEAL_COMPLETION_NULL: {
        deal_data datum = deal_data_of_null();
        deal_async_record_complete(record, &datum, 0, DEAL_PRESENT_DATUM,
            DEAL_UNICODE_MALFORMED);
        return;
    }
    case DEAL_COMPLETION_UNDEFINED: {
        deal_data datum = deal_data_of_undefined();
        deal_async_record_complete(record, &datum, 0, DEAL_PRESENT_DATUM,
            DEAL_UNICODE_MALFORMED);
        return;
    }
    case DEAL_COMPLETION_STRING: {
        const char *bytes = deal_async_frame_span(base, copy.string);
        if (!deal_text_is_scalar_sequence(bytes, copy.string.length)) {
            /* A malformed encoding is not a string datum: the await-origin
             * boundary renders the invalid-unicode arm (R2, R4). */
            deal_data datum = deal_data_of_undefined();
            deal_async_record_complete(record, &datum, 0,
                DEAL_PRESENT_INVALID_UNICODE, DEAL_UNICODE_MALFORMED);
            return;
        }
        deal_string *text = deal_text_new(bytes, copy.string.length);
        deal_data datum = deal_data_of_reference(DEAL_DATA_STRING, text);
        deal_async_record_complete(record, &datum, 0, DEAL_PRESENT_DATUM,
            DEAL_UNICODE_MALFORMED);
        return;
    }
    case DEAL_COMPLETION_ERROR: {
        deal_error *error = deal_error_host_text(
            deal_async_frame_span(base, copy.code),
            deal_async_frame_span(base, copy.message),
            copy.has_expected != 0
                ? deal_async_frame_span(base, copy.expected) : NULL,
            copy.has_actual != 0
                ? deal_async_frame_span(base, copy.actual) : NULL);
        deal_data datum = deal_data_of_reference(DEAL_DATA_ERROR, error);
        deal_async_record_complete(record, &datum, 1, DEAL_PRESENT_DATUM,
            DEAL_UNICODE_MALFORMED);
        return;
    }
    }
    deal_host_protocol_fault("a completion frame with an unknown kind");
}

/* =========================================================================
 * The mutator-thread completion entries (H5)
 * ========================================================================= */

deal_status deal_async_complete_value(deal_async_token token, deal_value value) {
    if (deal_platform_on_mutator_thread() == 0) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_async_entry *entry = deal_async_find(token);
    if (entry == NULL || entry->record->completed != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_check_input checked;
    deal_error *cause = NULL;
    deal_status status = deal_host_value_presentation(value, &checked, &cause);
    if (status != DEAL_OK) {
        return status;
    }
    if (checked.presentation == DEAL_PRESENT_NOTHING) {
        /* The no-value marker of a result-only position is not a completion
         * value: a `Promise<void>` completes with `undefined` (H5). */
        return DEAL_STATUS_KIND;
    }
    if (checked.presentation == DEAL_PRESENT_INVALID_UNICODE) {
        /* A malformed text carrier is no datum: the record keeps the closed
         * presentation, so the await-origin boundary renders the invalid
         * Unicode arm instead of the completion being invented as a value. */
        deal_data datum = deal_data_of_undefined();
        deal_async_record_complete(entry->record, &datum, 0,
            DEAL_PRESENT_INVALID_UNICODE, checked.unicode_defect);
        return DEAL_OK;
    }
    if (checked.presentation != DEAL_PRESENT_DATUM) {
        return DEAL_STATUS_KIND;
    }
    deal_async_record_complete(entry->record, &checked.datum, 0,
        DEAL_PRESENT_DATUM, DEAL_UNICODE_MALFORMED);
    return DEAL_OK;
}

deal_status deal_async_complete_error(deal_async_token token,
                                      const deal_host_failure *failure) {
    if (failure == NULL) {
        return DEAL_STATUS_PROTOCOL;
    }
    if (deal_platform_on_mutator_thread() == 0) {
        return DEAL_STATUS_OUT_OF_SCOPE;
    }
    deal_async_entry *entry = deal_async_find(token);
    if (entry == NULL || entry->record->completed != 0u) {
        return DEAL_STATUS_PROTOCOL;
    }
    deal_error *error = NULL;
    deal_status status = deal_host_resolve_failure(failure, &error);
    if (status != DEAL_OK) {
        return status;
    }
    deal_data datum = deal_data_of_reference(DEAL_DATA_ERROR, error);
    deal_async_record_complete(entry->record, &datum, 1, DEAL_PRESENT_DATUM,
        DEAL_UNICODE_MALFORMED);
    return DEAL_OK;
}

/* =========================================================================
 * The async export invocation surface (H6 `ExportInvoke`)
 * ========================================================================= */

deal_outcome deal_async_export_call(deal_fn *function,
                                    const char *signature_descriptor,
                                    const deal_origin *origin) {
    deal_outcome outcome;
    outcome.error = NULL;
    if (function == NULL || signature_descriptor == NULL || origin == NULL) {
        deal_platform_fault("async: malformed async export invocation");
    }
    if (deal_fn_is_async(function) == 0) {
        deal_platform_fault("async: the async export surface of a synchronous"
                            " function");
    }
    deal_descriptor *signature = deal_descriptor_intern(signature_descriptor);
    size_t parameter_count = 0u;
    (void)deal_descriptor_parameters(signature, &parameter_count);
    if (parameter_count != 0u) {
        /* The export surface invokes zero-arity exports (H6). */
        deal_platform_fault("async: the export surface of a parameterized"
                            " async export");
    }
    const char *result = deal_descriptor_text(deal_descriptor_result(signature));
    deal_outcome_reference started = deal_async_start(function, NULL, 0u,
        origin);
    if (started.error != NULL) {
        outcome.error = started.error;
        return outcome;
    }
    deal_outcome_data awaited = deal_async_await(started.value, result,
        origin);
    outcome.error = awaited.error;
    return outcome;
}

deal_outcome deal_async_export_call_host(const deal_host_export *exported,
                                         const char *signature_descriptor,
                                         const deal_origin *origin) {
    deal_outcome outcome;
    outcome.error = NULL;
    if (exported == NULL || signature_descriptor == NULL || origin == NULL) {
        deal_platform_fault("async: malformed host async export invocation");
    }
    if (exported->kind != DEAL_HOST_EXPORT_ASYNC_FUNCTION
            || exported->is_async == 0 || exported->async_function == NULL) {
        deal_platform_fault("async: the async export surface of a non-async"
                            " export record");
    }
    deal_outcome_reference carrier = deal_host_export_value(exported,
        signature_descriptor, origin);
    if (carrier.error != NULL) {
        outcome.error = carrier.error;
        return outcome;
    }
    /* The carrier is one managed function value: this helper's own intermediate
     * across the allocating start and pump, so one scoped root holds it (M5). */
    deal_root_scope_push();
    uint32_t held = deal_root_scope_hold(carrier.value);
    outcome = deal_async_export_call(carrier.value, signature_descriptor,
        origin);
    deal_root_scope_release(held);
    deal_root_scope_pop();
    return outcome;
}
