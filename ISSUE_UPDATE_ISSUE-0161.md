---
id: ISSUE-0161
kind: task
title: Production async export invocation on LuaJIT and JVM
status: in_progress
priority: 3
labels:
  - deal-v1.2
  - host-abi
  - async
  - luajit
  - jvm
depends_on: []
parent: ISSUE-0111
workdir: WD-0900
mr: MR-0387
assignee: BOT-3104
review_cycles: 1
run_attempts: 0
integration_attempts: 0
total_runs: 4
depth: 1
interventions: 0
integration_fix: false
replan_requested: false
wiki:
  - deal-v1.2-int32-and-bytes-architecture
  - deal-v1.2-directives-and-c-ffi-declarations
sources:
  - arkestr-breakdown-proposal:BC-000005:E9
researched: true
external_researched: true
architecture_status: pending
breakdown_candidate_id: null
created: 2026-08-21T14:37:11Z
updated: 2026-09-04T18:53:56Z
---

## Update: recorded blockers for the E3/E8 lanes (review cycle 1 remediation)

This record updates the issue's `Blockers` section and the criterion-3/4
tracking state per the MR-0387 review cycle 1 findings (BOT-3100,
2026-09-04T18:53:43Z). The two findings were verified against the
rebased canonical tree (`7946e265`): the JVM backend still rejects every
bytes-bearing signature with E6000 (`JvmBackend.silentJavaLocalType`
returns `null` for `Type.Bytes`), so the JVM half of the bytes-bearing
oracle cannot compile until ISSUE-0160 lands; and the sidecar/authoring
registry machinery is parent-owned, so this issue consumes the landed
REGISTRY boundary (ISSUE-0346/MR-0384, LuaJIT lane) instead of authoring
a catalog. The delivered remediation below wires the JVM lane of that
boundary and records the remaining blockers; nothing is narrowed by a
boundary note, and no criterion is marked met while its blocker is open.

### Delivered in this MR (post-rebase)

1. **JVM lane of the REGISTRY boundary** — `test/JvmRegistryAsyncExportBoundaryTest.java`
   (registered in `tools/gate-manifest.sh`): the same committed
   D12-shaped `async-export` record projects under
   `test/fixtures/registry/` execute through the production
   `ProjectLocator` (valid CLI backend override `jvm`) +
   `CompilationOrchestrator` (COMMON_SHADOW + DEAL_V1_2_INT32,
   PRE_ACTIVATION) + the production `JvmAsyncExportInvoker`:
   matcher-validated string completion (`Result.Value("string",
   "\"x\"")`), E8004 `int out of safe range` propagation with the
   JVM's absent location fields, and the pinned
   `HostInvocationFailure` reasons for sync/parameterized/
   descriptor-mismatched/missing exports — never a DEAL code and never
   a satisfied runtime-error expectation.
2. **Broken-dependency failure on both matrix-required backends** — the
   committed record project `test/fixtures/registry/broken-dependency/`
   (an oracle awaiting an imported async dependency that throws
   `TEST_FAIL`) propagates the exact DEAL error through both the
   production LuaJIT invoker (source location carried) and the
   production JVM invoker (absent location); a different declared code
   never satisfies the expectation.
3. **Backend-omission failure at the consumption boundary** — the
   mirrored D12 `BYTES_ASYNC_FUNCTION` matrix rule (the same semantic
   record on LuaJIT and JVM with async-export): a lane set missing
   either backend fails the family gate. The authoritative
   catalog/matrix validation is parent-owned (below).
4. **Honest E8 pins** — `test/JvmAsyncExportInvokerTest.java` and
   `test/JvmRegistryAsyncExportBoundaryTest.java` pin the JVM bytes
   record's E6000 rejection with the flip requirement below; the
   string/int async function-value oracle is labelled the interim
   JVM-lane production scenario, not a substitute for the bytes half.

### Recorded blockers (the new `Blockers` section)

1. **ISSUE-0160 (E8, JVM recursive bytes closure) — unlanded.** Blocks
   criterion 3's JVM half (the production `async()->null` oracle that
   assigns and containerizes legal first-class `async(bytes)->bytes`
   values, invokes and awaits one, checks bytes identity/content in
   source, and completes null through `JvmAsyncExportInvoker` with
   `Result.Value("null","null")`) and criterion 4's E8 consumption.
   Until it lands, the JVM bytes record stays E6000-pinned and the
   criterion-3 JVM half stays **unmet** — it must not be marked met.
2. **E3 record authoring, request supply, registry gating, and the
   architecture-owned `FeatureBackendMatrix` — parent-owned
   (ISSUE-0165 family; ISSUE-0346's boundary).** Blocks the catalog-side
   "backend omission fails validation" enforcement; this issue consumes
   the landed boundary on both lanes and mirrors the matrix rule at the
   consumption boundary. Criterion 4's E3 half is met through the
   committed record projects (LuaJIT lane: `RegistryAsyncExportBoundaryTest`;
   JVM lane: `JvmRegistryAsyncExportBoundaryTest`).

### Flip requirements (when ISSUE-0160 lands)

1. `test/JvmAsyncExportInvokerTest.java#jvmBytesOracleIsRejectedWithE6000UntilTheBytesLaneLands`
   becomes the production bytes-oracle test: the exact bytes-bearing
   oracle compiled through the same production path and invoked with
   the byte-exact canonical descriptor `"null"`, asserting
   `Result.Value("null", "null")`.
2. `test/JvmRegistryAsyncExportBoundaryTest.java#jvmLaneAsyncBytesRecordIsPinnedE6000UntilTheBytesClosureLands`
   becomes the production invocation of the committed
   `async-bytes-oracle` record on the JVM lane with
   `Result.Value("null", "null")`; the lane outcome flips from
   `BLOCKED(ISSUE-0160)` to `SUCCESS`.
3. Blocker 1 is removed, and criteria 3 (JVM half) and 4 (E8
   consumption) are marked met.

### Tracking state

- Criterion 1 (both-backend init/main exactly once, single selection and
  invocation, canonical E4 completion checking, sealed three-outcome
  distinction): **met** (LuaJitAsyncExportInvokerTest,
  JvmAsyncExportInvokerTest).
- Criterion 2 (every negative selection shape with byte-identical
  pinned reasons, no false runtime-error pass): **met** on both
  backends.
- Criterion 3: **met on LuaJIT only** — the JVM half is blocked on
  ISSUE-0160 (blocker 1) and must not be marked met.
- Criterion 4: the E3 consumption (both lanes through the committed
  record projects) and the broken-dependency/backend-omission failure
  modes are demonstrated; the E8 consumption is blocked on ISSUE-0160
  (blocker 1).
