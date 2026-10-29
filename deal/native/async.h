/*
 * deal/native/async.h — the `async` service group.
 *
 * Blocking async with runtime-owned operation tokens
 * (`llvm-native-host-abi-and-async` H5): one open-operation table keyed by the
 * runtime-allocated token, exactly one record per live token, a token that is
 * never reused or recycled, and one delivery path that always runs on the
 * waiting thread. `deal_async_post` and `deal_async_operation` are H2's entries
 * of `deal_rt.h`; the two mutator-thread completion entries are declared here.
 *
 * `deal_async_complete_value` completes the token's operation with a carrier of
 * any kind (managed kinds and identity-preserving Error delivery are
 * waiting-thread-only); `deal_async_complete_error` completes it with
 * host-authored error text or with a DEAL-origin failure token, storing the
 * identical Error when a token is relayed. Both return `DEAL_STATUS_PROTOCOL`
 * for an unknown or duplicate token and `DEAL_STATUS_OUT_OF_SCOPE` off the
 * mutator thread, and change nothing in either case.
 *
 * The open-operation table, the pump, the service hooks and the delivery path
 * land with the host-async child, which extends this header.
 */
#ifndef DEAL_NATIVE_ASYNC_H
#define DEAL_NATIVE_ASYNC_H

#include "deal/native/deal_rt.h"

/* The waiting thread only: completes the token's operation with a carrier. */
deal_status deal_async_complete_value(deal_async_token token, deal_value value);

/* The waiting thread only: completes it with host error text or a relayed DEAL
 * failure token. */
deal_status deal_async_complete_error(deal_async_token token,
                                     const deal_host_failure *failure);

/* =========================================================================
 * The C7 blocking lowering surface (llvm-native-codegen-cfg-ssa C7)
 * ========================================================================= */

/*
 * The two entries the emitted `ASYNC_START`/`AWAIT` operations call. Their
 * declarations are the module's lowering contract: the generated module emits
 * exactly these calls (the runtime-allocated operation record and token, the
 * blocking invocation to completion, and the await-origin completion check).
 * The definitions — the open-operation table, the token allocator, the pump,
 * the service hooks and the single delivery path — land with the host-async
 * task of `llvm-native-host-abi-and-async` H5, so a project whose facts contain
 * an async operation publishes valid IR and fails its native link with the
 * seam's pinned `deal: native link failed: <reason>` diagnostic until that
 * runtime group exists. No program silently misbehaves: an unresolved entry is
 * a link failure, never a substituted semantic.
 */

/*
 * `ASYNC_START`: allocates the operation record and its runtime-owned token,
 * invokes the callee (direct or dynamic) to completion recording the
 * completion value or the carried Error, and answers the operation handle.
 * The checked argument presentations and the call origin are the recorded
 * parameter boundaries and the start op's own origin.
 */
deal_outcome_reference deal_async_start(deal_fn *callee,
                                        const deal_check_input *arguments,
                                        uint32_t argument_count,
                                        const deal_origin *origin);

/*
 * `AWAIT`: observes the operation, drives pending host work when required,
 * validates the completion against the recorded completion descriptor at the
 * await origin (the `ASYNC_COMPLETION` boundary) and answers the completion as
 * persistent tagged data. A carried Error is raised at the await expression and
 * reaches the nearest enclosing catch unchanged.
 */
deal_outcome_data deal_async_await(void *operation,
                                   const char *completion_descriptor,
                                   const deal_origin *origin);

#endif /* DEAL_NATIVE_ASYNC_H */
