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

#endif /* DEAL_NATIVE_ASYNC_H */
