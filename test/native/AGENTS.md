# Native Fixtures

## Build and execution

Use the deployment-produced runtime tree and pinned strict-C11 flags at O0/O2,
not a hand-assembled runtime. Drivers replace process entry only; never replace
runtime symbols. Hosts include generated declaration headers and define matching
registrations once. File stems match declaration module stems; empty registries/
metadata use the published NULL/count-zero shape.

Retain normal/stress and plain/ASan/UBSan matrices with identical observations.
Queue/poster race probes also use TSan; their compilation requirements and the
ASan-only use-after-poison negative remain explicit. Negative build/link fixtures
must actually fail. Protocol/invariant cases require exact stderr and exit 2.
Driver expectations come from independent shared contracts, not observed output.

`module_stub.c` supplies emitted-module exports/arms for runtime drivers, including
arm-table and partial-link negatives. Product builds use published `main.c`;
driver builds use their own entry. Keep product and driver terminal observations,
including independently reconstructed lossless snapshots.

## Collector

- Root live values across allocation; variable-layout walkers trace initialized
  slots only. Exact live-set counts require completed collection.
- Stress starts whenever idle and uses normal bounded slices. Assert reference-
  work limits for growing graphs and resumed wide-object walks, not timing.
- Preserve isolated white-referent barrier and allocation-mark controls under both
  schedules, with exact survival/reclamation deltas. Retain root-source cycle-start
  and atomic-rescan assertions, capacity reuse, zero-payload growth and arena release.
- Keep seeded mutation/deletion models, cycle/alias identity and payload checks.
  Reclaimed payload poison must be detected by the negative control.
- `error_reclaim_driver.c` counts platform malloc/free through link wrappers,
  without replacing runtime symbols; assert exact live/reclaimed/reused auxiliary
  Error storage footprints.

## JSON and text

The JSON driver uses exact contextual targets/installed plans and builtin
stringify, never public std/json wrappers. Retain the full observation matrix at
O0/O2 normal/stress/plain/sanitized, including **100,000-level decode** and
**60,000-level scratch** workloads. Locale runs require a non-dot ambient decimal
separator yet byte-identical numeric observations.

Construction callbacks force full collection: compare reachability with the same
parsed numeric-field document to prove lookup scratch is reclaimable while inputs
remain rooted. Do not replace this with timing/allocation thresholds.

Pin scanner/type/extra-field failures, extras-first order, deep leaf origins,
required presence, unchanged destinations/aliases and same-graph repair after
collection. Exact plans and actual stored class/function kinds matter; never
fabricate class tags on Record storage or retag reference-array elements.
Signed callable omission/substitution controls assert noninvocation. Builtin Error
and nullable function/reference arrays retain actual identities and output.

Error/text controls retain embedded NUL and lone units through mutation, reads,
collection, accessors and snapshots. Assert post-NUL bytes, malformed transport,
full-span arm parameters and invalid accessor arguments. Byte pointer replacements
must update their explicit lengths. Compare host encodings by UTF-16 units.

## Hosts and async

`probe.c`/`slot.c` consume generated headers; `host_value_driver.c` exercises closed
construction/view/handle/failure-token statuses and resource limits. Function-typed
slots use FUNCTION_SIGNATURE checks. Registration defects remain protocol failures.
`gc_control.c`/`gc_crossing.c` exercise real activation collection, retained graphs,
relay/discard and alias survival.

`async_probe.c` covers real token-bound completions, service hooks, copied posts,
mutator-thread checks, public re-entry, out-of-start-order paired completion and
original Error relay. Preserve closed status and fatal-protocol negatives;
malformed graph completions resolve once and reject at await, not disappear.

Admit valid graphs before fixture-only hostile mutation. Preserve descriptors,
unrelated slots, cycles and aliases through rejection/repair/collection. Recheck
a shared value under stronger descriptors; earlier field defects precede later
required absence. Pin actual nominal/kind tokens, indexed causes and origins.

Raw callable fixtures use executable unsigned host entries; opaque fixtures use
borrowed nonnull identities, never forged tags. Both reject unread graph admission,
retain/apply and incompatible view operations before publication/mutation. Repair
same carriers and retain signed-wrapper positives. Nonstring Record-key rejection
is independent of hidden value legality and preserves ordinary string-key transport.
