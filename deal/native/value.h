/*
 * deal/native/value.h — the `value` service group.
 *
 * The value group owns the two nullish data singletons, the persistent tagged
 * payload of an `unknown` position, the scalar-nullish tags and the one host
 * value table of each active crossing (`llvm-native-runtime-value-model` R2,
 * R3, R11). The remaining C3 representations belong to their groups: `text`
 * owns the immutable string, `container` the typed array and the record, the
 * emitted module the per-layout class instance, `error` the builtin Error
 * instance and the function-value child the function value.
 *
 * Representations (C3). `null` and `undefined` are two distinct immutable data
 * singletons, never C `NULL` and never each other; absence is only presence
 * metadata, so a slot, an optional field and a record member never encode
 * absence as either singleton. A scalar nullish position carries one of the
 * three independent tags below, and an `unknown` position carries the
 * persistent internal tagged payload `deal_data` — never a crossing-scoped
 * handle — so it preserves nominal and function identity and an array's
 * element descriptor (page `llvm-native-memory-management` M4). Its `reference`
 * member is the managed payload for the managed tags and is always `NULL`
 * otherwise, so a precise reference map may always mark it and a tag-aware scan
 * never reads scalar bits, a nullish singleton or a crossing handle as a heap
 * address.
 *
 * The crossing value table (R3, R11, M5) is this group's: one table of
 * `(slot, generation, referent)` entries per active crossing, with issuance,
 * resolution, foreign rejection and crossing-scoped release. Every view entry
 * roots the managed object it names and each supplemental root keeps one
 * materialized object alive until the crossing ends; the table is a registered
 * root source for as long as its crossing is active, and a carrier issued by a
 * finished crossing resolves `DEAL_STATUS_FOREIGN_VALUE` — a stale slot and
 * generation are never dereferenced. Crossing orchestration, carrier
 * construction, validation, handles and error tokens belong to the host child,
 * which consumes these entries.
 *
 * `deal_value` (the H2 crossing carrier) and the host-visible constructors and
 * accessors are declared in `deal_rt.h`; this header declares the runtime-side
 * value surface the emitted module and the other groups call.
 */
#ifndef DEAL_NATIVE_VALUE_H
#define DEAL_NATIVE_VALUE_H

#include "deal/native/deal_rt.h"

/*
 * The one immutable runtime singleton for language `null` (C3). Generated code
 * stores this pointer at a reference position for `null` and never C `NULL`, so
 * `null` is distinguishable from an absent slot, an absent optional field and
 * the internal missing of a comparison-operand read.
 *
 * The spelling is deliberately not `deal_null`: that name is H2's boxed-carrier
 * construction entry of `deal_rt.h`.
 */
extern void *const deal_null_singleton;

/*
 * The one immutable runtime singleton for language `undefined` (C3). Generated
 * code stores this pointer at a reference position for `undefined`; it is a
 * distinct value from the null singleton and from C `NULL`.
 *
 * The spelling is deliberately not `deal_undefined`: that name is H2's
 * boxed-carrier construction entry of `deal_rt.h`.
 */
extern void *const deal_undefined_singleton;

/* 1 when `reference` is the null singleton, 0 otherwise (never C NULL). */
int deal_value_is_null(const void *reference);

/* 1 when `reference` is the undefined singleton, 0 otherwise. */
int deal_value_is_undefined(const void *reference);

/* The persistent tagged datum, presence metadata and scalar-nullish tags are
 * the C3 value-surface records declared by `deal_rt.h`; this group owns their
 * constructors, predicates and projections below. */

/* The tag one scalar nullish slot stores for this datum (C3). */
deal_nullish_tag deal_nullish_of_data(const deal_data *value);

/* The `undefined` datum; a distinct data value, never absence. */
deal_data deal_data_of_undefined(void);

/* The `null` datum; a distinct data value, never absence. */
deal_data deal_data_of_null(void);

/* One boolean datum, stored as 0 or 1. */
deal_data deal_data_of_boolean(int truth);

/* One binary64 number datum (including signed zero, NaN and infinities). */
deal_data deal_data_of_number(double value);

/*
 * One reference datum of a managed tag (string, array, record/table, class
 * instance, function value or the builtin `Error` instance) holding
 * `referent`. A nullish tag has no referent and is built through its own
 * constructor. A non-managed tag or a null `referent` is an invariant fault.
 */
deal_data deal_data_of_reference(deal_data_tag tag, void *referent);

/* 1 when the datum is one of the two nullish data values, 0 otherwise. */
int deal_data_is_nullish(const deal_data *value);

/*
 * 1 when the datum names a collector-managed object (string, array, record,
 * class instance, function value or the builtin `Error` instance), 0 otherwise.
 */
int deal_data_is_managed(const deal_data *value);

/*
 * The managed payload of the datum, or NULL for every non-managed tag. It is
 * the one value a precise root slot, reference-map slot or crossing entry
 * roots.
 */
void *deal_data_referent(const deal_data *value);

/* -- the crossing value table (R3, R11, M5) ------------------------------ */

/* One active host crossing's value table; its layout exists only here. */
typedef struct deal_value_crossing deal_value_crossing;

/*
 * Pushes one crossing scope and returns its value table. The scope registers as
 * a root source until it pops, so every referent it names is rooted (M5).
 */
deal_value_crossing *deal_value_crossing_begin(void);

/*
 * Pops the crossing scope: its value and supplemental-root tables are released
 * and every carrier it issued becomes foreign. Popping anything but the active
 * crossing is an invariant fault, so no failed crossing leaves a scope open.
 * Popping writes no managed state, so it neither allocates nor faults for a
 * resource reason.
 */
void deal_value_crossing_end(deal_value_crossing *crossing);

/* The active crossing's value table, or NULL when no crossing is active. */
deal_value_crossing *deal_value_crossing_active(void);

/* The number of entries the active crossing issued (0 when none is active). */
size_t deal_value_crossing_count(void);

/*
 * Issues one view carrier for the managed `referent` and roots it for the
 * crossing's lifetime. A non-active crossing answers `DEAL_STATUS_OUT_OF_SCOPE`
 * and a null referent `DEAL_STATUS_KIND`, neither publishing an entry; table
 * exhaustion is a resource fault (R10), so it terminates with one deterministic
 * stderr line and exit 2 before any partial publication. A view entry whose
 * referent is a nullish singleton roots nothing: the crossing scope's scan is
 * tag-aware (M5), exactly like every other root source.
 */
deal_status deal_value_crossing_issue(deal_value_crossing *crossing,
                                      void *referent, deal_value *out);

/*
 * Issues one carrier whose referent is crossing-scoped platform storage (a
 * host-built boxed carrier, R11) or has no referent at all. The payload is
 * resolved unchanged and is never traced as a managed reference.
 */
deal_status deal_value_crossing_issue_opaque(deal_value_crossing *crossing,
                                             void *payload, deal_value *out);

/*
 * Roots one further managed object for the crossing's lifetime (an object of a
 * materialized graph, R11/M5). A non-active crossing answers
 * `DEAL_STATUS_OUT_OF_SCOPE` and a null referent `DEAL_STATUS_KIND`, neither
 * rooting anything; exhausting the crossing root table is a resource fault
 * (R10) — one deterministic stderr line and exit 2 — so a status never stands
 * for an exhausted table.
 */
deal_status deal_value_crossing_root(deal_value_crossing *crossing,
                                     void *referent);

/*
 * Resolves one carrier against the active crossing: on success `*out` is the
 * entry's referent (or crossing-scoped payload) and nothing else changes. A
 * carrier of another crossing, a carrier of a finished crossing, an unknown
 * slot and a stale generation all answer `DEAL_STATUS_FOREIGN_VALUE` and write
 * nothing.
 */
deal_status deal_value_crossing_resolve(deal_value_crossing *crossing,
                                        deal_value value, void **out);

#endif /* DEAL_NATIVE_VALUE_H */
