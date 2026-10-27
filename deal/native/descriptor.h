/*
 * deal/native/descriptor.h — the `descriptor` service group.
 *
 * Canonical descriptor atoms and the one descriptor-driven check
 * (`llvm-native-runtime-value-model` R4). One parser reads exactly the canonical
 * public descriptor grammar of `docs/spec-v1.5.md`: `null`, `undefined`,
 * `boolean`, `number`, `string`, `record`, `unknown`, `Error`, a nominal
 * `@module/path/Class`, the array form `[T]`, the nullish prefixes `?T`, `!T`
 * and `?!T`, the union `null|undefined`, and the function signature
 * `(params)->result` with its optional `async` marker. `void` is result-only and
 * is rejected in data, array and parameter positions; a nullish prefix needs a
 * non-nullish, non-unknown base and does not nest; source `Promise<R>` notation
 * is represented by an async function signature and is never a storable
 * descriptor. The emitter passes descriptor text as static data and this group
 * interns it into atoms.
 *
 * `deal_check_value` is the single descriptor-driven check for compound
 * descriptors (arrays, nullish forms, records, classes, functions, class values
 * from untyped boundaries, record reads in a contextual type and JSON field
 * validation). It publishes exactly the arm's code, template instantiation and
 * expected/actual projection, in the recorded order, with the recorded origin;
 * the arm text itself comes from the installed failure-arm table (error.h) and
 * no group authors it in C.
 *
 * The atom parser, the check and the primitive scalar helpers arrive with the
 * runtime-boundaries-errors child, which extends this header.
 */
#ifndef DEAL_NATIVE_DESCRIPTOR_H
#define DEAL_NATIVE_DESCRIPTOR_H

#include "deal/native/deal_rt.h"

/* An interned canonical descriptor atom; the layout is private to the group. */
typedef struct deal_descriptor deal_descriptor;

/* One check input: either a boxed carrier or a native reference (C3). */
typedef struct {
    int boxed;             /* 1 = carrier is set, 0 = reference is set */
    deal_value carrier;
    void *reference;
} deal_check_input;

/* Interns canonical descriptor text; a malformed/degenerate text is a producer
 * defect reported deterministically (it never becomes a DEAL Error). */
deal_descriptor *deal_descriptor_intern(const char *descriptor_text);

/* R4: descriptor-driven compound check at one boundary kind and origin. */
deal_outcome_reference deal_check_value(deal_descriptor *expected,
                                        deal_check_input input,
                                        uint32_t boundary_kind,
                                        const deal_origin *origin);

#endif /* DEAL_NATIVE_DESCRIPTOR_H */
