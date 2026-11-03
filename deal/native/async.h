/*
 * deal/native/async.h — the `async` service group.
 *
 * Blocking async with runtime-owned operation tokens
 * (`llvm-native-host-abi-and-async` H5): one open-operation table keyed by the
 * runtime-allocated token, exactly one record per live token, a token that is
 * never reused or recycled, and one delivery path that always runs on the
 * waiting thread. `deal_async_post` and `deal_async_operation` are H2's entries
 * of `deal_rt.h`; this header declares the C7 lowering surface, the two
 * mutator-thread completion entries, the pump's own bound and the two entries
 * of H6's async export invocation surface.
 *
 * `deal_async_complete_value` completes the token's operation with a carrier of
 * any kind (managed kinds and identity-preserving Error delivery are
 * waiting-thread-only); `deal_async_complete_error` completes it with
 * host-authored error text or with a DEAL-origin failure token, storing the
 * identical Error when a token is relayed. Both return `DEAL_STATUS_PROTOCOL`
 * for an unknown or duplicate token and `DEAL_STATUS_OUT_OF_SCOPE` off the
 * mutator thread, and change nothing in either case.
 *
 * `deal_async_post` is the only completion entry any thread may call: it
 * deep-copies the closed host-native record into platform frame storage,
 * publishes it and never reads or writes managed state or the open-operation
 * table. Delivery — the frame's token resolved against the live table, the
 * payload materialized, the completion stored through the insertion barrier and
 * the record marked completed exactly once — happens on the waiting thread,
 * inside the pump that `AWAIT` drives there.
 */
#ifndef DEAL_NATIVE_ASYNC_H
#define DEAL_NATIVE_ASYNC_H

#include "deal/native/deal_rt.h"

/* The pump's bounded wait slice (H5): the interval `AWAIT` blocks for between
 * service-hook polls and queue drains. It decides only how often service hooks
 * are polled; it never changes observable behavior and the pump never spins. */
#define DEAL_PUMP_SLICE_NS 10000000

/* The waiting thread only: completes with a carrier, or its materialization
 * Error if recursive graph admission fails. Such a rejection consumes the
 * completion exactly once without publishing the rejected graph. */
deal_status deal_async_complete_value(deal_async_token token, deal_value value);

/* The waiting thread only: completes it with host error text or a relayed DEAL
 * failure token. */
deal_status deal_async_complete_error(deal_async_token token,
                                     const deal_host_failure *failure);

/* =========================================================================
 * The C7 blocking lowering surface (llvm-native-codegen-cfg-ssa C7)
 * ========================================================================= */

/*
 * `ASYNC_START`: allocates the operation record and its runtime-owned token,
 * invokes the callee to completion recording the completion value or the
 * carried Error, and answers the operation handle. A callee that is the carrier
 * of a declared host async export takes the token-bound host crossing of H5
 * (the record stays pending until its completion is delivered); every other
 * callee is a DEAL function value whose blocking body runs to completion here.
 * The checked argument presentations and the call origin are the recorded
 * parameter boundaries and the start op's own origin.
 */
deal_outcome_reference deal_async_start(deal_fn *callee,
                                        const deal_check_input *arguments,
                                        uint32_t argument_count,
                                        const deal_origin *origin);

/*
 * `AWAIT`: observes the operation, drives pending host work when required
 * (service hooks in registration order, every queued frame, then one bounded
 * platform wait), validates the completion against the recorded completion
 * descriptor at the await origin (the `ASYNC_COMPLETION` boundary) and answers
 * the completion as persistent tagged data. A carried Error is raised at the
 * await expression and reaches the nearest enclosing catch unchanged. The
 * operation's open-table entry is removed and its token retired forever.
 */
deal_outcome_data deal_async_await(void *operation,
                                   const char *completion_descriptor,
                                   const deal_origin *origin);

/* =========================================================================
 * The async export invocation surface (H6 `ExportInvoke`)
 * ========================================================================= */

/*
 * One zero-arity async export invoked and pumped to completion (H6): the
 * operation starts over the export's own function value and the await-origin
 * completion check runs against the result atom of
 * `signature_descriptor` (the export entry's own declared canonical
 * signature), so a `Promise<void>` export completes as undefined and never as
 * `null`. Answers the carried failure. Value-bearing forms write the actual
 * checked completion on success only; callers root it across allocation.
 * Non-async values and parameterized signatures are producer defects.
 */
deal_outcome deal_async_export_call(deal_fn *function,
                                    const char *signature_descriptor,
                                    const deal_origin *origin);

/*
 * The same invocation of one declared host async export: the export record's
 * own carrier is created (H4) and started, so its async entry is called with
 * the runtime-allocated token and its completion is pumped to the await origin
 * exactly as an awaiting DEAL caller's is. A non-async export record is a
 * producer defect.
 */
deal_outcome deal_async_export_call_host(const deal_host_export *exported,
                                         const char *signature_descriptor,
                                         const deal_origin *origin);

/* Value-bearing counterparts; a NULL value pointer discards the completion. */
deal_outcome deal_async_export_value(deal_fn *function,
                                     const char *signature_descriptor,
                                     const deal_origin *origin, deal_data *value);
deal_outcome deal_async_export_value_host(const deal_host_export *exported,
                                          const char *signature_descriptor,
                                          const deal_origin *origin,
                                          deal_data *value);

#endif /* DEAL_NATIVE_ASYNC_H */
