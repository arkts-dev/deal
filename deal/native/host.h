/*
 * deal/native/host.h — the `host` service group.
 *
 * The native host load surface and the crossing machinery
 * (`llvm-native-host-abi-and-async` H1, H2, H3, H4, H7). The host-visible value
 * surface itself is H2's canonical declaration set in `deal_rt.h`; this header
 * carries the two declared-module records the emitted module hands to the
 * runtime loader, the one load entry the loader implements, and the two
 * crossing entries the emitted module calls for a synchronous host call and for
 * the DEAL-origin failure relay of a host-driven re-entry.
 *
 * Load contract (H1, H7, `LoadHostModule`): at module initialization the
 * importing unit passes its generated declared-module record and the origin of
 * the importing statement. The runtime resolves the raw specifier against the
 * compiled registry (`deal_host_registry`/`deal_host_registry_count`), validates
 * the declared export set, kinds, descriptors and class metadata, and answers
 * one validated binding whose members are all H2-declared types. The importing
 * unit's emitted walk builds and publishes that import's own `deal_module`
 * export table from the binding; the runtime never exposes a module layout
 * (C2/C3/H6 opacity). A declared and imported host module whose implementation
 * unit was not supplied has no registry entry and fails here with the closed
 * E8011 `HOST_LOAD_FAILED` arm at the importing origin.
 *
 * The binding is platform memory created once at module initialization, holds
 * no managed pointer, and lives for the process lifetime; allocation failure is
 * the R10 resource fault.
 *
 * Crossings (`HostCallSync`, `HostValueSurface`, R11). One synchronous host
 * call pushes one crossing scope, hands every declared parameter position a
 * carrier of the current crossing, calls the host once, validates the returned
 * carrier against the declared return descriptor through the one
 * `deal_check_value` entry, admits the result (a view keeps the DEAL identity, a
 * host-built carrier is materialized into the managed representation with alias
 * identity, self-reference and host-object non-mutation preserved), and pops the
 * scope. Handles (`deal_retain`, `deal_handle_value`, `deal_release`) and the
 * DEAL-origin failure token table are owned here beside the value table the
 * `value` group exposes, and both are root sources of the collector (M5).
 */
#ifndef DEAL_NATIVE_HOST_H
#define DEAL_NATIVE_HOST_H

#include "deal/native/deal_rt.h"

/*
 * One generated declared-module record: the raw import specifier, the
 * declaration module's module-id path, the declared exports in declaration
 * order (names, canonical descriptors and kinds; inactive implementation
 * pointers are NULL) and the declared class metadata. Emitted as static data by
 * the importing unit's module walk.
 */
typedef struct {
  const char *specifier;                     /* raw specifier, e.g. "host/http" */
  const char *module_path;                   /* module-id path used for labels */
  const deal_host_export *declared_exports;  /* declared exports, in order */
  size_t declared_export_count;
  const deal_host_class *declared_classes;   /* declared class metadata */
  size_t declared_class_count;
} deal_host_declared_module;

/*
 * The runtime-known validated binding of one loaded host module: the raw
 * specifier, the validated registration and one validated export record per
 * declared export, in declaration order. It contains no `deal_module` and no
 * managed pointer.
 */
typedef struct {
  const char *specifier;
  const deal_host_module *module;
  const deal_host_export *const *exports;   /* one per declared export */
  size_t export_count;
} deal_host_binding;

/*
 * The native host load entry (H1, H7): resolves, validates and answers one
 * runtime-known binding, or the carried E8011 Error that marks the importing
 * unit failed. `*out` is written on success only.
 */
deal_outcome deal_host_load(const deal_host_declared_module *declared,
                            const deal_origin *origin,
                            const deal_host_binding **out);

/* =========================================================================
 * The synchronous crossing
 * ========================================================================= */

/*
 * The synchronous external call of one validated host export (H2
 * `HostCallSync`, H4). The emitted module has already run every declared
 * parameter position's `HOST_PARAMETER` boundary check, so `arguments` carries
 * the admitted presentations in declaration order; `signature_descriptor` is
 * the export's declared canonical signature (the same text the declaration
 * surface, the generated header and the export entry record carry) and
 * `origin` is the call statement's own origin.
 *
 * The entry pushes one crossing, presents the parameters as that crossing's
 * carriers, calls the export once, applies H2's failure protocol (host-authored
 * text becomes the carried Error, a relayed DEAL failure is the identical
 * Error, an unconsumed token is the deterministic protocol failure reported
 * before the return value is checked), validates and admits the returned
 * carrier at the `HOST_SYNC_RETURN` boundary, and pops the crossing on every
 * path. The declared result position comes from the signature's own result
 * atom through the published descriptor grammar, so a function-typed result is
 * checked as that function, and the result-only `void` is the no-value position
 * projected to the `undefined` datum. A rejected return admits nothing.
 */
deal_outcome_data deal_host_invoke_sync(const deal_host_export *exported,
                                        const deal_check_input *arguments,
                                        uint32_t argument_count,
                                        const char *signature_descriptor,
                                        const deal_origin *origin);

/*
 * The function value of one declared, validated host export (H2/H4): the
 * carrier whose boxed apply thunk invokes the host entry with the crossing
 * carriers of the checked argument presentations and applies the declared
 * result boundary. It is the emitted import read's projection of
 * `<host module>.<export>`, so a DEAL call reaches the host through the one
 * function-value protocol instead of a second call path.
 */
deal_outcome_reference deal_host_export_value(const deal_host_export *exported,
                                              const char *signature_descriptor,
                                              const deal_origin *origin);

/*
 * Issues one DEAL-origin failure token in the current crossing and fills one
 * `DEAL_FAILURE_DEAL_ERROR` result whose value is the class carrier naming that
 * very Error (H2/H4). It is the emitted `CALLBACK_INVOKE` entry's failure path,
 * so a host re-entering DEAL code receives exactly the record `deal_apply`
 * answers. A null Error, a non-Error value and a missing crossing are producer
 * defects.
 */
deal_status deal_host_relay_error(deal_error *error, deal_host_result *out);

/*
 * The boxed apply thunk of the recorded function a host-driven re-entry
 * invokes: the same shape the emitted module supplies for every function value
 * (the `value` group's `deal_fn_thunk`), restated here because a group header
 * declares only H2's own records and its own function-pointer types.
 */
typedef deal_outcome_data (*deal_host_boxed_thunk)(void *function,
                                                   const deal_check_input *arguments,
                                                   uint32_t argument_count,
                                                   const deal_origin *origin);

/*
 * The host-driven re-entry of one recorded DEAL function (H4 `CALLBACK_INVOKE`,
 * `HostCallSync`). The emitted module emits one entry per recorded
 * `CALLBACK_INVOKE` with the published `deal_host_function` shape, so a host
 * calls it with crossing carriers exactly as it calls `deal_apply`, and this
 * entry answers the same `deal_host_result`:
 *
 *   - `thunk` is the recorded function's own boxed apply thunk and `function`
 *     its published function value, so the thunk's `HOST_TO_DEAL` parameter
 *     boundaries, the body and the `DEAL_TO_HOST` return boundary run exactly
 *     as they do for a free function value;
 *   - a DEAL failure inside the re-entered code answers `failed = 1` with
 *     `DEAL_FAILURE_DEAL_ERROR`, the runtime-issued single-use token of this
 *     crossing and the `DEAL_KIND_CLASS` carrier of that same Error, so the
 *     host relays the record unchanged or discards the token under H2's
 *     relay-or-discard rule;
 *   - a successful re-entry answers `failed = 0` and the admitted return
 *     carrier.
 *
 * The entry runs inside the mutator's current crossing (a host only runs there),
 * so a missing crossing answers `DEAL_STATUS_OUT_OF_SCOPE`, an argument pair of
 * the wrong shape `DEAL_STATUS_PROTOCOL` and an argument carrier that cannot be
 * presented `DEAL_STATUS_KIND`; nothing is written in those cases.
 */
deal_status deal_host_callback_invoke(deal_host_result *out,
                                      deal_host_boxed_thunk thunk, void *function,
                                      const deal_value *arguments,
                                      size_t argument_count,
                                      const deal_origin *origin);

/* =========================================================================
 * The emitted class access surface
 * ========================================================================= */

/*
 * One emitted class's host-visible access seams: the declared layout, an
 * allocation seam that creates and tags one instance with every field absent,
 * a field-store seam that writes one already validated presentation (and its
 * presence flag) into the declared slot with the insertion barrier, and the
 * field-read seam the JSON walks already use. A class instance is opaque to
 * every runtime group, so the emitted module owns the slots and the nominal
 * tag; the runtime owns the descriptor checks, the materialization order and
 * the barriers' invocation.
 */
typedef deal_outcome (*deal_host_class_create)(void *context, void **out);

typedef deal_outcome (*deal_host_class_store)(void *context, void *instance,
                                              size_t index,
                                              const deal_check_input *value,
                                              int present);

typedef int (*deal_host_class_read)(const deal_data *instance, size_t index,
                                    deal_check_input *value, uint8_t *present,
                                    void *context);

typedef struct {
  const deal_class_layout *declared;   /* class atom + declaration-order fields */
  deal_host_class_create create;       /* allocation + nominal tag           */
  deal_host_class_store store;         /* validated field store + barrier    */
  deal_host_class_read read;           /* field extraction (R11)             */
  void *context;                       /* the emitted module's own context   */
} deal_host_class_access;


/*
 * Registers one emitted class's access seams by canonical class atom before any
 * user code (`deal_class_plans_init`). Registering the same atom again with the
 * same record is idempotent; a different record for one atom, a null seam and a
 * blank atom are invariant faults.
 */
void deal_host_class_access_register(const char *class_atom,
                                     const deal_host_class_access *access);

/*
 * The registered access record of one canonical class atom, or NULL when the
 * project emits no such class. The runtime's host crossings and the value
 * surface read it and never inspect an instance's layout themselves.
 */
const deal_host_class_access *deal_host_class_access_of(const char *class_atom);

/*
 * The typed presentation of one persistent datum (R11/R4): a datum
 * presentation whose class atom and function signature are taken from the value
 * itself, exactly as a crossing's own classification derives them, so a check
 * at a class or function position compares that value's nominal identity and
 * signature. The `async` group's await-origin completion check reuses this
 * projection, so the `ASYNC_COMPLETION` boundary reads one presentation set
 * and never derives a second classification.
 */
void deal_host_present_datum(deal_data datum, deal_check_input *out);

/*
 * Resolves one host failure record of the current crossing into the Error it
 * names and consumes a DEAL-origin token (H2/R5): host-authored text becomes a
 * builtin Error carrying exactly the host's text, and a relayed token resolves
 * to the identical Error. `DEAL_STATUS_OK` writes the resolved Error;
 * `DEAL_STATUS_PROTOCOL` means the token is not live in this crossing or was
 * already consumed, and `DEAL_STATUS_OUT_OF_SCOPE` means no crossing is active.
 * A malformed record (an unknown kind, host text without its code or message, a
 * relayed record carrying code/message text) is the deterministic host protocol
 * failure of H2. Nothing is written on a status answer.
 */
deal_status deal_host_resolve_failure(const deal_host_failure *failure,
                                      deal_error **out);

/*
 * The persistent presentation of one carrier of the current crossing (R11/R4):
 * a managed view keeps its datum form and a host-built carrier is materialized
 * into the managed representation with one identity per boxed node for the
 * crossing, including separate view stores and return. Failed graph conversions
 * publish no partial identity; later accesses use that live managed object,
 * including mutations made through DEAL re-entry. `DEAL_STATUS_OK` writes the
 * presentation plan a boundary check reads; no crossing answers `DEAL_STATUS_OUT_OF_SCOPE`, a
 * carrier of another crossing `DEAL_STATUS_FOREIGN_VALUE`, and a carrier that is
 * never DEAL data (the absence marker, the no-value marker or the untyped-array
 * negative presentation) `DEAL_STATUS_KIND`; a failing materialization also
 * answers `DEAL_STATUS_KIND` and, through `cause`, that arm's own carried
 * Error. Nothing is published by a rejected carrier. The materialized graph
 * stays rooted for the crossing's lifetime (M5), so a caller stores the datum
 * before its scope ends.
 */
deal_status deal_host_value_presentation(deal_value value,
                                         deal_check_input *out,
                                         deal_error **cause);

/*
 * 1 when `function` is the runtime's own carrier of a declared host async
 * export (H2/H4), 0 for every other function value. The carrier wraps the host
 * async entry in one runtime-private record whose layout identity distinguishes
 * it from a captured-binding cell, so the `async` group routes `ASYNC_START`
 * over this carrier to the host async crossing below instead of the boxed apply
 * thunk.
 */
int deal_host_is_async_function_value(deal_fn *function);

/*
 * The host async crossing (H2/H5, `HostCallAsync`): the runtime has already
 * allocated the operation record and its token, so this entry pushes the
 * crossing, presents the checked parameter carriers, calls the carrier's own
 * async entry with the token, applies H2's failure protocol (host-authored text
 * becomes the carried Error, a relayed failure is the identical Error, an
 * unconsumed token is the protocol failure reported first) and checks the
 * returned carrier: `DEAL_KIND_ASYNC_OPERATION` naming exactly `token`
 * succeeds, every other kind renders the closed `ASYNC_OPERATION_HANDLE` arm at
 * `origin` (E8010, typed-boundary actual projection) and a substituted token is
 * the deterministic protocol failure. Answers the carried Error on failure and
 * NULL on success; the crossing is released on both paths.
 */
deal_error *deal_host_invoke_async_value(deal_fn *callee,
                                         deal_async_token token,
                                         const deal_check_input *arguments,
                                         uint32_t argument_count,
                                         const deal_origin *origin);

/*
 * The pump's crossing-equivalent scope (H5, M5): pushes one crossing for the
 * duration of one pump cycle, so a module service hook constructs, inspects,
 * completes with and re-enters DEAL code through crossing carriers exactly as
 * it does inside one of its export calls, and every carrier the cycle issued
 * becomes foreign when the cycle ends. The scope is popped on the completing
 * and on the still-pending path alike; a cycle that still roots an unconsumed
 * DEAL-origin failure token is the deterministic protocol failure. `origin` may
 * be NULL for a pump cycle, which carries the file-less origin of the runtime.
 */
void deal_host_scope_push(const deal_origin *origin);
void deal_host_scope_pop(void);

/*
 * The one deterministic host protocol failure (H2/H5): one stderr line,
 * `DEAL_HOST_PROTOCOL: <reason>`, and exit 2. The `async` group reports an
 * unattributable completion, a duplicate delivery and a reentrant pump through
 * this same spelling, so the native lane has exactly one protocol surface.
 */
_Noreturn void deal_host_protocol_fault(const char *reason);

#endif /* DEAL_NATIVE_HOST_H */
