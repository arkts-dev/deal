/*
 * test/native/async_probe.c — the host implementation unit of the
 * "host/async" declaration module (ISSUE-0913, H2/H5).
 *
 * A real host unit: it includes only the generated declaration header
 * `host/async.h` (published beside the project module and reached through the
 * build seam's published-root include path) and defines the registration record
 * exactly once. Every declared export is implemented in every case; the
 * `DEAL_HOST_ASYNC_CASE` selector changes the behavior under test:
 *
 *   0  conforming completions: a number posted synchronously, the undefined
 *      completion of `Promise<void>`, null, posted undefined, a managed carrier
 *      completed through `deal_async_complete_value` on the mutator thread, a
 *      host-authored error completion, and a string posted from storage the
 *      poster releases before it returns
 *   1  `pending` stays pending and the module's own service hook posts the
 *      completion (waiting-thread managed completion path)
 *   2  `pending` spawns a thread that posts the completion; the poster asserts
 *      it is not the mutator thread and the service hook joins it
 *   3  `wrongKind` posts a string for the declared number completion
 *   4  `malformed` posts invalid UTF-8 bytes for the declared string completion
 *   5  `statuses` asserts the closed completion statuses of H5
 *   6  `duplicate` posts a second completion for the same token
 *   7  `unknownToken` posts a completion naming no live operation
 *   8  `substituted` returns an operation carrier built for another token
 *   9  `applyAsync` retains the DEAL function value it is handed and the
 *      module's service hook applies it while the pump is active, so the
 *      re-entered DEAL body awaits a pending operation (H5's non-reentrancy
 *      rule)
 *  10  `late` completes synchronously and the service hook later posts a second
 *      completion for its retired token while `pending` is awaited (an
 *      unattributable completion)
 *  11  `wrongShape` returns a number carrier from a declared async export (the
 *      closed ASYNC_OPERATION_HANDLE arm)
 *  12  two host operations stay pending at once with distinct tokens: `pairFirst`
 *      re-enters DEAL from its own crossing (H4) and applies the DEAL async
 *      function `applyAsync` retained, whose await starts `pairSecond`; the
 *      service hook posts `pairFirst`'s completion first, while `pairSecond` is
 *      the operation being awaited (H5 Verification 6, no cross-delivery)
 *  13  `relay` applies the retained DEAL function `armFailure`, retains the
 *      Error its failure carries, discards the issuing token and completes the
 *      pending operation from the service hook with `deal_error_from_handle`
 *      plus `deal_async_complete_error` (the identical Error); the hook
 *      completes `textCompletion` through the same entry's host-text arm
 *  14  `ingress` answers the closed completion statuses for an async-operation
 *      carrier and for the no-value marker, which are never data, and then
 *      completes for real
 */
#include "host/async.h"

#include <stdlib.h>
#include <string.h>
#include <threads.h>
#ifdef DEAL_HOST_POST_DURING_COLLECTION
#include <stdatomic.h>
#endif

#ifndef DEAL_HOST_ASYNC_CASE
#define DEAL_HOST_ASYNC_CASE 0
#endif

static deal_host_result ok_result(deal_value value) {
  deal_host_result result;
  result.failed = 0;
  result.value = value;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = NULL;
  result.failure.message = NULL;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0u;
  return result;
}

static deal_host_result failure_result(const char *code, const char *message) {
  deal_host_result result;
  result.failed = 1;
  result.value.slot = 0u;
  result.value.generation = 0u;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = code;
  result.failure.message = message;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0u;
  return result;
}

/* One number carrier answered at a declared `number` result position. */
static deal_host_result number_result(double value) {
  deal_value carrier;
  if (deal_number(value, &carrier) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "number carrier construction failed");
  }
  return ok_result(carrier);
}

/* The carrier an async export returns: the runtime-issued operation of the
 * token the runtime allocated for this call. */
static deal_host_result operation_result(deal_async_token token) {
  deal_host_result result = ok_result((deal_value){0u, 0u});
  if (deal_async_operation(token, &result.value) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "async operation construction failed");
  }
  return result;
}

static int post_number(deal_async_token token, double value) {
  deal_host_completion completion;
  completion.kind = DEAL_COMPLETION_NUMBER;
  completion.as.number = value;
  return deal_async_post(token, &completion) == DEAL_OK ? 1 : 0;
}

static int post_undefined(deal_async_token token) {
  deal_host_completion completion;
  completion.kind = DEAL_COMPLETION_UNDEFINED;
  completion.as.number = 0.0;
  return deal_async_post(token, &completion) == DEAL_OK ? 1 : 0;
}

/* =========================================================================
 * The conforming completions (case 0)
 * ========================================================================= */

static deal_host_result sync_impl(deal_async_token token,
                                  const deal_value *args, size_t argc) {
  double value = 0.0;
  if (argc != 1u || deal_as_number(args[0], &value) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "the sync export takes one number");
  }
  if (!post_number(token, value + 1.0)) {
    return failure_result("E_HOST_ASYNC", "posting the number completion failed");
  }
  return operation_result(token);
}

static deal_host_result void_impl(deal_async_token token,
                                  const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (!post_undefined(token)) {
    return failure_result("E_HOST_ASYNC", "posting undefined failed");
  }
  return operation_result(token);
}

static deal_host_result null_impl(deal_async_token token,
                                  const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_host_completion completion;
  completion.kind = DEAL_COMPLETION_NULL;
  completion.as.number = 0.0;
  if (deal_async_post(token, &completion) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "posting null failed");
  }
  return operation_result(token);
}

static deal_host_result bool_impl(deal_async_token token,
                                  const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_host_completion completion;
  memset(&completion, 0, sizeof completion);
  completion.kind = DEAL_COMPLETION_BOOLEAN;
  completion.as.boolean = 1;
  if (deal_async_post(token, &completion) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "posting the boolean failed");
  }
  return operation_result(token);
}

static deal_host_result undefined_impl(deal_async_token token,
                                       const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (!post_undefined(token)) {
    return failure_result("E_HOST_ASYNC", "posting undefined failed");
  }
  return operation_result(token);
}

/* One managed completion on the mutator thread: a typed array carrier the
 * runtime materializes into the managed representation with its persistent
 * element descriptor. */
static deal_host_result managed_impl(deal_async_token token,
                                     const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  deal_value element;
  if (deal_array_new_typed(2, "number", &array) != DEAL_OK
      || deal_number(4.0, &element) != DEAL_OK
      || deal_array_set(array, 0, element) != DEAL_OK
      || deal_number(5.0, &element) != DEAL_OK
      || deal_array_set(array, 1, element) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "the managed carrier failed");
  }
  if (deal_async_complete_value(token, array) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "the managed completion failed");
  }
  return operation_result(token);
}

/* A host-authored error completion: the host's own code and message, no arm,
 * no origin and no frames (R5). */
static deal_host_result text_failure_impl(deal_async_token token,
                                          const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_host_completion completion;
  memset(&completion, 0, sizeof completion);
  completion.kind = DEAL_COMPLETION_ERROR;
  completion.as.error.code = "E_HOST_ASYNC_TEXT";
  completion.as.error.message = "the host async operation failed";
  completion.as.error.expected = NULL;
  completion.as.error.actual = NULL;
  if (deal_async_post(token, &completion) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "posting the error text failed");
  }
  return operation_result(token);
}

/* The copy-in rule: the poster owns its storage only until post returns. The
 * bytes are copied into the platform frame, then the poster's own buffer is
 * overwritten, and the mutator must still observe the original text. */
static deal_host_result copied_text_impl(deal_async_token token,
                                         const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  static const char original[] = "copied-completion";
  char *bytes = malloc(sizeof original);
  if (bytes == NULL) {
    return failure_result("E_HOST_ASYNC", "the text buffer failed");
  }
  memcpy(bytes, original, sizeof original);
  deal_host_completion completion;
  memset(&completion, 0, sizeof completion);
  completion.kind = DEAL_COMPLETION_STRING;
  completion.as.string.data = bytes;
  completion.as.string.length = sizeof original - 1u;
  deal_status status = deal_async_post(token, &completion);
  memset(bytes, 'X', sizeof original);
  free(bytes);
  if (status != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "posting the copied text failed");
  }
  return operation_result(token);
}

/* =========================================================================
 * The pending completions (cases 1, 2)
 * ========================================================================= */

static deal_async_token pending_token;
static int pending_done;

#if DEAL_HOST_ASYNC_CASE == 2
static thrd_t poster_thread;
static int poster_off_mutator;
#ifdef DEAL_HOST_POST_DURING_COLLECTION
static atomic_int collection_entered;
static atomic_int completion_posted;
static void collection_rendezvous(deal_reference_visit visit,
                                   void *visit_context, void *context) {
  (void)visit; (void)visit_context; (void)context;
  atomic_store_explicit(&collection_entered, 1, memory_order_release);
  while (atomic_load_explicit(&completion_posted, memory_order_acquire) == 0) {
    thrd_yield();
  }
}
#endif

static int poster(void *context) {
  (void)context;
  /* The poster must observe that it is not the mutator thread (H5). */
  poster_off_mutator = deal_platform_on_mutator_thread() == 0 ? 1 : 0;
#ifdef DEAL_HOST_POST_DURING_COLLECTION
  while (atomic_load_explicit(&collection_entered, memory_order_acquire) == 0) {
    thrd_yield();
  }
#endif
  int status = post_number(pending_token, 21.0) == 1 ? 0 : 1;
#ifdef DEAL_HOST_POST_DURING_COLLECTION
  atomic_store_explicit(&completion_posted, 1, memory_order_release);
#endif
  return status;
}
#endif

static deal_host_result pending_impl(deal_async_token token,
                                     const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  pending_token = token;
  pending_done = 0;
#if DEAL_HOST_ASYNC_CASE == 2
#ifdef DEAL_HOST_POST_DURING_COLLECTION
  atomic_store_explicit(&collection_entered, 0, memory_order_relaxed);
  atomic_store_explicit(&completion_posted, 0, memory_order_relaxed);
#endif
  if (thrd_create(&poster_thread, poster, NULL) != thrd_success) {
    return failure_result("E_HOST_ASYNC", "the poster thread failed");
  }
#ifdef DEAL_HOST_POST_DURING_COLLECTION
  /* The root-source callback runs inside the collector, holds the mutator
   * there until the other thread publishes, and touches no managed state. */
  deal_root_source_register(collection_rendezvous, NULL);
  deal_gc_collect_full();
  deal_root_source_unregister(collection_rendezvous, NULL);
#endif
#endif
  return operation_result(token);
}

/* =========================================================================
 * The closed completion statuses (case 5)
 * ========================================================================= */

/* One off-mutator managed delivery attempt: it must answer
 * `DEAL_STATUS_OUT_OF_SCOPE` and change nothing. */
struct status_thread {
  deal_async_token token;
  deal_value value;
  deal_status status;
};

static int status_poster(void *context) {
  struct status_thread *probe = context;
  probe->status = deal_async_complete_value(probe->token, probe->value);
  return 0;
}

static deal_host_result statuses_impl(deal_async_token token,
                                      const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value carrier = {0u, 0u};
  deal_host_failure failure;
  memset(&failure, 0, sizeof failure);
  failure.kind = DEAL_FAILURE_HOST_TEXT;
  failure.code = "E_HOST_ASYNC_STATUS";
  failure.message = "status probe";
  /* An unknown token is the closed protocol status, and nothing is stored. */
  if (deal_async_complete_value(token + 1000, carrier) != DEAL_STATUS_PROTOCOL) {
    return failure_result("E_HOST_ASYNC_STATUS", "the unknown token status");
  }
  /* A DEAL-origin failure token this crossing never issued. */
  failure.kind = DEAL_FAILURE_DEAL_ERROR;
  failure.error = 987654321u;
  if (deal_async_complete_error(token, &failure) != DEAL_STATUS_PROTOCOL) {
    return failure_result("E_HOST_ASYNC_STATUS", "the foreign failure token");
  }
  /* A managed completion off the mutator thread is out of scope. */
  if (deal_number(9.0, &carrier) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_STATUS", "the status carrier");
  }
  struct status_thread probe = {token, carrier, DEAL_OK};
  thrd_t thread;
  if (thrd_create(&thread, status_poster, &probe) != thrd_success) {
    return failure_result("E_HOST_ASYNC_STATUS", "the status thread");
  }
  (void)thrd_join(thread, NULL);
  if (probe.status != DEAL_STATUS_OUT_OF_SCOPE) {
    return failure_result("E_HOST_ASYNC_STATUS", "the off-mutator status");
  }
  if (deal_number(7.0, &carrier) != DEAL_OK
      || deal_async_complete_value(token, carrier) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_STATUS", "the first completion");
  }
  /* A second completion for the same live operation is the protocol status. */
  if (deal_number(8.0, &carrier) != DEAL_OK
      || deal_async_complete_value(token, carrier) != DEAL_STATUS_PROTOCOL) {
    return failure_result("E_HOST_ASYNC_STATUS", "the duplicate completion");
  }
  return operation_result(token);
}

/* =========================================================================
 * The protocol-violation cases (6, 7, 8)
 * ========================================================================= */

static deal_host_result duplicate_impl(deal_async_token token,
                                       const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token, 1.0) || !post_number(token, 2.0)) {
    return failure_result("E_HOST_ASYNC", "the duplicate post failed");
  }
  return operation_result(token);
}

static deal_host_result unknown_token_impl(deal_async_token token,
                                           const deal_value *args,
                                           size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token + 1000, 1.0) || !post_number(token, 2.0)) {
    return failure_result("E_HOST_ASYNC", "the unknown-token post failed");
  }
  return operation_result(token);
}

static deal_host_result substituted_impl(deal_async_token token,
                                         const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_host_result result;
  result.failed = 0;
  result.value.slot = 0u;
  result.value.generation = 0u;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = NULL;
  result.failure.message = NULL;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0u;
  if (deal_async_operation(token + 1000, &result.value) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "the substituted carrier failed");
  }
  return result;
}

/* =========================================================================
 * The retained DEAL async function value (H3, cases 9 and 12)
 * ========================================================================= */

/* The host keeps the DEAL async function value it is handed as an opaque
 * handle (H3), so a later crossing re-enters DEAL through it: the re-entrant
 * case (9) does so from the pump's service hook, the paired case (12) from its
 * own async-export crossing. */
static deal_handle retained_async;

static deal_host_result apply_async_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return failure_result("E_HOST_ASYNC", "the apply export takes one value");
  }
  if (deal_retain(args[0], &retained_async) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "the retention failed");
  }
  return number_result(1.0);
}

/* =========================================================================
 * The retired-token case (case 10) and the wrong-shape case (case 11)
 * ========================================================================= */

#if DEAL_HOST_ASYNC_CASE == 10
static deal_async_token late_token;

static deal_host_result late_impl(deal_async_token token,
                                  const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  late_token = token;
  if (!post_number(token, 11.0)) {
    return failure_result("E_HOST_ASYNC", "posting the first completion failed");
  }
  return operation_result(token);
}
#else
static deal_host_result late_impl(deal_async_token token,
                                  const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token, 11.0)) {
    return failure_result("E_HOST_ASYNC", "posting the completion failed");
  }
  return operation_result(token);
}
#endif

static deal_host_result wrong_shape_impl(deal_async_token token,
                                         const deal_value *args, size_t argc) {
  (void)token;
  (void)args;
  (void)argc;
  /* A declared async export that answers a carrier which is not an async
   * operation: the closed ASYNC_OPERATION_HANDLE arm (H5). */
  return number_result(3.0);
}

/* =========================================================================
 * The two concurrently pending operations (case 12, H5 Verification 6)
 * ========================================================================= */

#if DEAL_HOST_ASYNC_CASE == 12
static deal_async_token pair_first_token;
static deal_async_token pair_second_token;
static int pair_posted;

/* Publishes the number completion, or host failure text when the number record
 * could not be published, so a defect is reported instead of waiting forever.
 * The fallback cannot duplicate: it runs only when the number post failed. */
static void post_number_or_fail(deal_async_token token, double value) {
  if (post_number(token, value)) {
    return;
  }
  deal_host_completion failure;
  memset(&failure, 0, sizeof failure);
  failure.kind = DEAL_COMPLETION_ERROR;
  failure.as.error.code = "E_HOST_ASYNC_PAIR";
  failure.as.error.message = "the paired completion could not be posted";
  (void)deal_async_post(token, &failure);
}

/* The first operation stays pending while the host re-enters DEAL: the retained
 * DEAL async function's own await starts the second host operation, so the two
 * operations are live at once with distinct runtime-issued tokens. */
static deal_host_result pair_first_impl(deal_async_token token,
                                        const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  pair_first_token = token;
  deal_value carrier = {0u, 0u};
  deal_host_result applied;
  memset(&applied, 0, sizeof applied);
  if (retained_async == 0u
      || deal_handle_value(retained_async, "async()->number", &carrier)
          != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_PAIR",
        "the retained DEAL async function did not resolve");
  }
  if (deal_apply(carrier, NULL, 0u, &applied) != DEAL_OK
      || applied.failed != 0) {
    return failure_result("E_HOST_ASYNC_PAIR",
        "the re-entered DEAL async body failed");
  }
  double awaited = 0.0;
  if (deal_as_number(applied.value, &awaited) != DEAL_OK || awaited != 222.0) {
    return failure_result("E_HOST_ASYNC_PAIR",
        "the re-entered DEAL await did not receive its own completion");
  }
  return operation_result(token);
}

static deal_host_result pair_second_impl(deal_async_token token,
                                         const deal_value *args,
                                         size_t argc) {
  (void)args;
  (void)argc;
  pair_second_token = token;
  if (pair_first_token == 0 || token == pair_first_token) {
    return failure_result("E_HOST_ASYNC_PAIR",
        "the two pending operations do not have distinct tokens");
  }
  return operation_result(token);
}

static void module_service(void) {
  if (pair_posted != 0 || pair_first_token == 0 || pair_second_token == 0) {
    return;
  }
  pair_posted = 1;
  /* The first operation's completion is posted first, so it arrives while the
   * second operation is the one being awaited. A delivery that resolved a frame
   * to the awaited record instead of the frame's own token would swap the two
   * values and fail both awaits. */
  post_number_or_fail(pair_first_token, 111.0);
  post_number_or_fail(pair_second_token, 222.0);
}
#else
static deal_host_result pair_first_impl(deal_async_token token,
                                        const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token, 111.0)) {
    return failure_result("E_HOST_ASYNC", "posting the first completion failed");
  }
  return operation_result(token);
}

static deal_host_result pair_second_impl(deal_async_token token,
                                         const deal_value *args,
                                         size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token, 222.0)) {
    return failure_result("E_HOST_ASYNC", "posting the second completion"
        " failed");
  }
  return operation_result(token);
}
#endif

/* =========================================================================
 * The relayed Error and the host-text completion (case 13, H2/H5)
 * ========================================================================= */

#if DEAL_HOST_ASYNC_CASE == 13
/* The DEAL function the relay applies, and the Error carrier the crossing keeps
 * across the two crossings as an opaque handle (H3). */
static deal_handle retained_failure;
static deal_handle retained_error;
static deal_async_token relay_token;
static deal_async_token text_token;
static int relay_posted;
static int text_posted;

static deal_host_result arm_failure_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return failure_result("E_HOST_ASYNC_RELAY",
        "the retain export takes one function");
  }
  if (deal_retain(args[0], &retained_failure) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_RELAY",
        "retaining the DEAL function failed");
  }
  return number_result(1.0);
}

/* The relay crossing (H2): apply the retained DEAL function, keep the Error its
 * failure carries by identity, discard the issuing token so nothing stays open,
 * and leave the operation pending for the service hook. */
static deal_host_result relay_impl(deal_async_token token,
                                   const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  relay_token = token;
  deal_value carrier = {0u, 0u};
  deal_host_result applied;
  memset(&applied, 0, sizeof applied);
  if (retained_failure == 0u
      || deal_handle_value(retained_failure, "()->number", &carrier)
          != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_RELAY",
        "the retained DEAL function did not resolve");
  }
  if (deal_apply(carrier, NULL, 0u, &applied) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_RELAY", "the DEAL function apply"
        " failed");
  }
  if (applied.failed == 0
      || applied.failure.kind != DEAL_FAILURE_DEAL_ERROR) {
    return failure_result("E_HOST_ASYNC_RELAY",
        "the DEAL function carried no DEAL failure");
  }
  if (deal_retain(applied.value, &retained_error) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_RELAY",
        "retaining the Error carrier failed");
  }
  if (deal_failure_discard(applied.failure.error) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_RELAY",
        "discarding the issuing token failed");
  }
  return operation_result(token);
}

static deal_host_result text_completion_impl(deal_async_token token,
                                             const deal_value *args,
                                             size_t argc) {
  (void)args;
  (void)argc;
  text_token = token;
  return operation_result(token);
}

/* Completes the token with host-authored text, the closed copy-in path, so a
 * defect in the managed entry is reported instead of waiting forever. */
static void complete_with_text(deal_async_token token, const char *code,
                               const char *message) {
  deal_host_completion completion;
  memset(&completion, 0, sizeof completion);
  completion.kind = DEAL_COMPLETION_ERROR;
  completion.as.error.code = code;
  completion.as.error.message = message;
  (void)deal_async_post(token, &completion);
}

static void module_service(void) {
  if (relay_posted == 0 && relay_token != 0) {
    relay_posted = 1;
    /* The DEAL-origin arm: `deal_error_from_handle` issues a token in this
     * crossing that names the identical Error, and the completion entry stores
     * that same object under the operation's own token. */
    deal_host_failure failure;
    memset(&failure, 0, sizeof failure);
    if (deal_error_from_handle(retained_error, &failure) != DEAL_OK) {
      complete_with_text(relay_token, "E_HOST_ASYNC_RELAY",
          "the retained Error did not resolve");
    } else if (deal_async_complete_error(relay_token, &failure) != DEAL_OK) {
      (void)deal_failure_discard(failure.error);
      complete_with_text(relay_token, "E_HOST_ASYNC_RELAY",
          "the relayed completion was refused");
    }
  }
  if (text_posted == 0 && text_token != 0) {
    text_posted = 1;
    /* The host-text arm of the same entry (R5): a builtin Error with the host's
     * own code and message and no invented arm, origin, frames or cause. */
    deal_host_failure failure;
    memset(&failure, 0, sizeof failure);
    failure.kind = DEAL_FAILURE_HOST_TEXT;
    failure.code = "E_HOST_ASYNC_COMPLETE";
    failure.message = "the service hook completed with text";
    if (deal_async_complete_error(text_token, &failure) != DEAL_OK) {
      complete_with_text(text_token, "E_HOST_ASYNC_RELAY",
          "the host-text completion was refused");
    }
  }
}
#else
static deal_host_result arm_failure_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  return number_result(1.0);
}

static deal_host_result relay_impl(deal_async_token token,
                                   const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token, 31.0)) {
    return failure_result("E_HOST_ASYNC", "posting the relay completion"
        " failed");
  }
  return operation_result(token);
}

static deal_host_result text_completion_impl(deal_async_token token,
                                             const deal_value *args,
                                             size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token, 32.0)) {
    return failure_result("E_HOST_ASYNC", "posting the text completion"
        " failed");
  }
  return operation_result(token);
}
#endif

/* =========================================================================
 * The completion ingress (case 14, H5/R3)
 * ========================================================================= */

#if DEAL_HOST_ASYNC_CASE == 16
/* Recursive admission fails before publication. Completion still resolves once
 * with the carried Error, including when its graph was never read by DEAL. */
static deal_host_result ingress_impl(deal_async_token token,
                                     const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value graph, bad;
  if (deal_table_new(&graph) != DEAL_OK
      || deal_string_new("\xed\xa0\x80", 3u, &bad) != DEAL_OK
      || deal_table_set(graph, "unread", 6u, bad) != DEAL_OK
      || deal_async_complete_value(token, graph) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_INGRESS",
        "nested malformed completion lost its Error");
  }
  deal_value number;
  if (deal_number(7.0, &number) != DEAL_OK
      || deal_async_complete_value(token, number) != DEAL_STATUS_PROTOCOL) {
    return failure_result("E_HOST_ASYNC_INGRESS",
        "a rejected graph completion was not exactly once");
  }
  deal_gc_collect_full();
  return operation_result(token);
}
#elif DEAL_HOST_ASYNC_CASE == 14
/* Neither an async-operation carrier nor the no-value marker is a completion
 * datum (V1.5, R3): both answer the closed kind status and change nothing, so
 * the real completion afterwards is still the one the await observes. */
static deal_host_result ingress_impl(deal_async_token token,
                                     const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value carrier = {0u, 0u};
  if (deal_async_operation(token, &carrier) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_INGRESS",
        "the operation carrier construction failed");
  }
  if (deal_async_complete_value(token, carrier) != DEAL_STATUS_KIND) {
    return failure_result("E_HOST_ASYNC_INGRESS",
        "an operation carrier was admitted as data");
  }
  if (deal_nothing(&carrier) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_INGRESS",
        "the no-value carrier construction failed");
  }
  if (deal_async_complete_value(token, carrier) != DEAL_STATUS_KIND) {
    return failure_result("E_HOST_ASYNC_INGRESS",
        "the no-value marker was admitted as data");
  }
  if (deal_number(7.0, &carrier) != DEAL_OK
      || deal_async_complete_value(token, carrier) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC_INGRESS",
        "the operation no longer completes after the rejections");
  }
  return operation_result(token);
}
#else
static deal_host_result ingress_impl(deal_async_token token,
                                     const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (!post_number(token, 7.0)) {
    return failure_result("E_HOST_ASYNC", "posting the ingress completion"
        " failed");
  }
  return operation_result(token);
}
#endif

/* =========================================================================
 * The remaining cases' behavior
 * ========================================================================= */

static deal_host_result wrong_kind_impl(deal_async_token token,
                                        const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_host_completion completion;
  memset(&completion, 0, sizeof completion);
  completion.kind = DEAL_COMPLETION_STRING;
  completion.as.string.data = "seven";
  completion.as.string.length = 5u;
  if (deal_async_post(token, &completion) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "posting the string failed");
  }
  return operation_result(token);
}

static deal_host_result malformed_impl(deal_async_token token,
                                       const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  static const unsigned char surrogate[] = {0xEDu, 0xA0u, 0x80u};
  deal_host_completion completion;
  memset(&completion, 0, sizeof completion);
  completion.kind = DEAL_COMPLETION_STRING;
  completion.as.string.data = surrogate;
  completion.as.string.length = sizeof surrogate;
  if (deal_async_post(token, &completion) != DEAL_OK) {
    return failure_result("E_HOST_ASYNC", "posting the malformed text failed");
  }
  return operation_result(token);
}

/* =========================================================================
 * The delivered-completion case (case 15, M5 Verification 11)
 * ========================================================================= */

#if DEAL_HOST_ASYNC_CASE == 15
/* The open-operation record must root a *delivered* completion until the
 * consuming AWAIT reads it (M5 `OpenOperationRecord`, H5). The service hook
 * completes the token with a managed class instance, then re-enters the DEAL
 * callback the program armed, whose body runs one complete collection while the
 * record still holds the delivered value and the pump has not re-checked the
 * operation yet. A record that does not root its completion slot loses the
 * instance at that collection. */
static deal_async_token record_token;
static int record_armed;
static deal_handle retained_callback;

static deal_host_result arm_impl(const deal_value *args, size_t argc) {
  deal_value carrier;
  if (argc != 1u || deal_retain(args[0], &retained_callback) != DEAL_OK) {
    return failure_result("E_HOST_RECORD",
        "arming the collection callback failed");
  }
  if (deal_null(&carrier) != DEAL_OK) {
    return failure_result("E_HOST_RECORD", "the null result failed");
  }
  record_armed = 1;
  return ok_result(carrier);
}

static deal_host_result pending_managed_impl(deal_async_token token,
                                             const deal_value *args,
                                             size_t argc) {
  (void)args;
  (void)argc;
  record_token = token;
  record_armed = 1;
  return operation_result(token);
}

static void record_failure(const char *message) {
  deal_host_completion failure;
  memset(&failure, 0, sizeof failure);
  failure.kind = DEAL_COMPLETION_ERROR;
  failure.as.error.code = "E_HOST_RECORD";
  failure.as.error.message = message;
  (void)deal_async_post(record_token, &failure);
  record_token = 0;
}
#endif

/* =========================================================================
 * The service hook (H5)
 * ========================================================================= */

#if DEAL_HOST_ASYNC_CASE == 15
static void module_service(void) {
  if (record_armed == 0 || record_token == 0) {
    return;
  }
  deal_async_token token = record_token;
  record_token = 0;
  deal_value atom;
  deal_value payload;
  deal_value number;
  if (deal_class_atom(DEAL_HOST_CLASS_ATOM_ASYNC_PAYLOAD, &atom) != DEAL_OK
      || deal_class_new(atom, &payload) != DEAL_OK
      || deal_number(42.0, &number) != DEAL_OK
      || deal_field_set(payload, "value", number) != DEAL_OK) {
    record_failure("the delivered completion construction failed");
    return;
  }
  if (deal_async_complete_value(token, payload) != DEAL_OK) {
    record_failure("the delivered completion failed");
    return;
  }
  /* Re-enter DEAL *after* the completion is stored in the open-operation
   * record and *before* the pump re-checks the operation: the callback runs one
   * complete collection, so it observes exactly the delivery window. */
  deal_value callback;
  deal_host_result applied;
  memset(&applied, 0, sizeof applied);
  if (deal_handle_value(retained_callback, "()->null", &callback) != DEAL_OK
      || deal_apply(callback, NULL, 0u, &applied) != DEAL_OK
      || applied.failed != 0) {
    record_failure("the collection callback failed");
  }
}
#elif DEAL_HOST_ASYNC_CASE == 1 || DEAL_HOST_ASYNC_CASE == 2 \
    || DEAL_HOST_ASYNC_CASE == 9 || DEAL_HOST_ASYNC_CASE == 10
static void module_service(void) {
  if (pending_token == 0 || pending_done != 0) {
    return;
  }
#if DEAL_HOST_ASYNC_CASE == 10
  /* The late completion: `late` has already been consumed, so its token names
   * no live operation and the second frame is unattributable (H5). The
   * awaited operation is completed in the same cycle, so its frame is taken
   * first and this frame second. */
  if (late_token != 0 && late_token != pending_token) {
    (void)post_number(late_token, 12.0);
    (void)post_number(pending_token, 21.0);
    pending_done = 1;
    return;
  }
#elif DEAL_HOST_ASYNC_CASE == 9
  /* The re-entrant case: a service hook runs inside the pump, so re-entering a
   * DEAL body that awaits a pending operation is the H5 protocol failure. The
   * retained handle is the H3 way to hold the carrier across crossings. */
  static int invoked;
  if (invoked != 0) {
    return;
  }
  invoked = 1;
  deal_value carrier = {0u, 0u};
  deal_host_result applied;
  memset(&applied, 0, sizeof applied);
  if (retained_async != 0u
      && deal_handle_value(retained_async, "async()->number", &carrier)
          == DEAL_OK) {
    (void)deal_apply(carrier, NULL, 0u, &applied);
    return;
  }
  deal_host_completion failure;
  memset(&failure, 0, sizeof failure);
  failure.kind = DEAL_COMPLETION_ERROR;
  failure.as.error.code = "E_HOST_ASYNC_RETAINED";
  failure.as.error.message = "the retained async carrier did not resolve";
  (void)deal_async_post(pending_token, &failure);
  pending_done = 1;
#elif DEAL_HOST_ASYNC_CASE == 2
  int status = 0;
  if (thrd_join(poster_thread, &status) != thrd_success || status != 0
      || poster_off_mutator == 0) {
    deal_host_completion failure;
    memset(&failure, 0, sizeof failure);
    failure.kind = DEAL_COMPLETION_ERROR;
    failure.as.error.code = "E_HOST_ASYNC_POSTER";
    failure.as.error.message = "the poster thread ran on the mutator thread";
    (void)deal_async_post(pending_token, &failure);
    pending_done = 1;
    return;
  }
  pending_done = 1;
#else
  /* A managed completion from the service hook: the hook runs on the waiting
   * thread, so the completion entry accepts it there. */
  if (deal_platform_on_mutator_thread() == 0) {
    deal_host_completion failure;
    memset(&failure, 0, sizeof failure);
    failure.kind = DEAL_COMPLETION_ERROR;
    failure.as.error.code = "E_HOST_ASYNC_HOOK";
    failure.as.error.message = "the service hook ran off the waiting thread";
    (void)deal_async_post(pending_token, &failure);
    pending_done = 1;
    return;
  }
  deal_value carrier = {0u, 0u};
  if (deal_number(21.0, &carrier) == DEAL_OK
      && deal_async_complete_value(pending_token, carrier) == DEAL_OK) {
    pending_done = 1;
    return;
  }
  deal_host_completion failure;
  memset(&failure, 0, sizeof failure);
  failure.kind = DEAL_COMPLETION_ERROR;
  failure.as.error.code = "E_HOST_ASYNC_HOOK";
  failure.as.error.message = "the service-hook completion failed";
  (void)deal_async_post(pending_token, &failure);
  pending_done = 1;
#endif
}
#endif

#if DEAL_HOST_ASYNC_CASE == 15
const deal_host_field deal_host_fields_async_Payload[] = {
    {"value", "number", 0},
};

const deal_host_class deal_host_classes_async[] = {
    {DEAL_HOST_CLASS_ATOM_ASYNC_PAYLOAD, deal_host_fields_async_Payload,
     sizeof deal_host_fields_async_Payload
         / sizeof deal_host_fields_async_Payload[0]},
};

const deal_host_class deal_host_class_async_Payload = {
    DEAL_HOST_CLASS_ATOM_ASYNC_PAYLOAD, deal_host_fields_async_Payload,
    sizeof deal_host_fields_async_Payload
        / sizeof deal_host_fields_async_Payload[0]};
#endif

const deal_host_export deal_host_exports_async[] = {
    {"applyAsync", "(async()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     apply_async_impl, NULL, NULL},
    {"sync", "async(number)->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, sync_impl, NULL},
    {"voidOp", "async()->void", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, void_impl, NULL},
    {"boolOp", "async()->boolean", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, bool_impl, NULL},
    {"late", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, late_impl, NULL},
    {"wrongShape", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, wrong_shape_impl, NULL},
    {"nullOp", "async()->null", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, null_impl, NULL},
    {"undefinedOp", "async()->undefined", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, undefined_impl, NULL},
    {"pending", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, pending_impl, NULL},
    {"wrongKind", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, wrong_kind_impl, NULL},
    {"malformed", "async()->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, malformed_impl, NULL},
    {"textFailure", "async()->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, text_failure_impl, NULL},
    {"managed", "async()->unknown", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, managed_impl, NULL},
    {"copiedText", "async()->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, copied_text_impl, NULL},
    {"statuses", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, statuses_impl, NULL},
    {"duplicate", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, duplicate_impl, NULL},
    {"unknownToken", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, unknown_token_impl, NULL},
    {"substituted", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, substituted_impl, NULL},
    {"pairFirst", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, pair_first_impl, NULL},
    {"pairSecond", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, pair_second_impl, NULL},
    {"relay", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, relay_impl, NULL},
    {"textCompletion", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, text_completion_impl, NULL},
    {"ingress", "async()->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1,
     NULL, ingress_impl, NULL},
    {"armFailure", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     arm_failure_impl, NULL, NULL},
#if DEAL_HOST_ASYNC_CASE == 15
    {"Payload", "@$external/host/async/Payload", DEAL_HOST_EXPORT_CLASS, 0,
     NULL, NULL, &deal_host_classes_async[0]},
    {"arm", "(()->null)->null", DEAL_HOST_EXPORT_FUNCTION, 0, arm_impl, NULL,
     NULL},
    {"pendingManaged", "async()->@$external/host/async/Payload",
     DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, pending_managed_impl, NULL},
#endif
};

const deal_host_module deal_host_module_async = {
    DEAL_HOST_SPECIFIER_ASYNC, deal_host_exports_async,
    sizeof deal_host_exports_async / sizeof deal_host_exports_async[0],
#if DEAL_HOST_ASYNC_CASE == 15
    deal_host_classes_async,
    sizeof deal_host_classes_async / sizeof deal_host_classes_async[0],
#else
    NULL, 0u,
#endif
#if DEAL_HOST_ASYNC_CASE == 1 || DEAL_HOST_ASYNC_CASE == 2 \
    || DEAL_HOST_ASYNC_CASE == 9 || DEAL_HOST_ASYNC_CASE == 10 \
    || DEAL_HOST_ASYNC_CASE == 12 || DEAL_HOST_ASYNC_CASE == 13 \
    || DEAL_HOST_ASYNC_CASE == 15
    module_service
#else
    NULL
#endif
};
