---
id: ISSUE-0391
kind: epic
title: Capability registry transition rollback observation (ISSUE-0241 boundary)
status: tracking
priority: 4
labels:
  - delegated
  - rollback
  - capability-registry
depends_on:
  - ISSUE-0389
parent: ISSUE-0231
workdir: WD-0651
mr: null
assignee: BOT-2216
review_cycles: 0
run_attempts: 0
integration_attempts: 0
total_runs: 6
depth: 2
interventions: 0
integration_fix: false
replan_requested: false
wiki:
  - activation-mechanism-and-legacy-regression-authority
  - common-semantic-lowering-layer
  - capability-registry-transition-rollback-observation
sources:
  - arkestr-breakdown-proposal:BC-000072:E1
researched: true
external_researched: true
architecture_status: approved
breakdown_candidate_id: BC-000077
created: 2026-08-29T23:11:48Z
updated: 2026-08-30T07:18:28Z
---

## Objective

Resolve the delegated half of A3 — the `PROMOTED → SHADOW` capability-registry transition surface and the route-only rollback observation over a partially promoted registry — for the post-activation rollback release path (parent D2 step 4). Defining constraint: a pre-activation epic that must not fire the public flip, amend the F4 routing rules/the closed capability registry/the A1 matrix, delete retained v1.2 code, revive a legacy public profile, or implement the promotion transitions themselves (ISSUE-0241's domain) — only the rollback observation over them.

The sub-architecture resolves (a) where the demotion transition surface lives within ISSUE-0241's promotion-transition ownership and how the observation constructs promoted/demoted registry states without amending the closed release registry contract, and (b) the proof over the real planner: promoted registry → `SHARED`; a one-capability `PROMOTED → SHADOW` demotion → `LEGACY` at plan time, before emission, with profile `DEAL_V1_2_INT32`, release state `V1_2_ACTIVE`, source/AST unchanged — only `ModuleRoutePlan.entries` change; the proof runs over the A1 matrix's `PUBLIC_BUILD + V1_2_ACTIVE` row via the T1 activation machine and asserts the recorded `capabilityRegistryHash` and derived `releaseStateHash` recompute over the demoted registry — never unchanged hashes across a registry transition.

## Scope

Included:

- Transition-surface placement and boundary contract: the demotion surface is ISSUE-0241's single release-owned transition function (promotion and demotion are one surface) — pinned as `CapabilityRegistry.withState(capability, target, state)` with the six invariants (pure/total, immutable-producing, closed-shape, digest recomputation, no-op idempotence, no gate policy inside the surface).
- Observation state construction: derivation-only from `releaseRegistry()` through that surface — no amendment, reflection, or test double.
- Observation proof architecture: import-free int-using fixture; real orchestrator-produced facts; real `MigrationPlanner.planRoutes`; promoted `P` → `SHARED`; one-capability demotion `D` → `LEGACY`; per-target discrimination; pinned reading of "only `ModuleRoutePlan.entries` change" (decision-content fields vs. derived identifier fields); hash-recomputation discipline; A1 matrix and armed-gate negatives.

Explicit exclusions:

- The public activation flip (E12's release action; the release state stays `PRE_ACTIVATION`).
- The promotion/demotion transition implementation itself (ISSUE-0241's domain) — only the observation over it.
- Any amendment of the F4 routing rules, the closed capability registry, or the A1 matrix.
- Deletion of retained v1.2 code; any revival of a legacy public profile (`LEGACY_SAFE_INT` is never a rollback target after activation).

## Sequencing

1. Transition-surface contract (D1/D3) — the observation's construction seam; pinned first, consumed by everything below.
2. Observation architecture (D2/D4/D5) — depends on the contract and on the landed T1 activation machine (ISSUE-0389, this epic's dependency; delivered in the tree — `deal/semantic/CompilerProfileProvider.java`).
3. Observation execution — a refinement child epic implementing the pinned test; gated on ISSUE-0241's promotion-transition surface landing (the issue's blocker).

## Design sources

- capability-registry-transition-rollback-observation

## Epic criteria

- The demotion transition surface is resolved as ISSUE-0241's single release-owned transition function with the pinned boundary contract (placement, six invariants, errors, post-state).
- The observation constructs promoted/demoted registry states only by derivation from `releaseRegistry()` through that surface — the closed release registry contract stays unamended.
- The proof, over the real planner: promoted registry routes the module `SHARED`; a one-capability `PROMOTED → SHADOW` demotion reroutes it `LEGACY` at plan time, before emission, with zero diagnostics; profile `DEAL_V1_2_INT32`, release state `V1_2_ACTIVE`, source/AST unchanged; only `ModuleRoutePlan.entries` change among decision-content fields.
- The proof derives both invocations through the A1 matrix's `PUBLIC_BUILD + V1_2_ACTIVE` row (T1) and asserts `capabilityRegistryHash`, `releaseStateHash`, `invocationHash`, and `planId` recompute and differ across the transition — no assertion ever claims unchanged hashes.
- Boundary negatives asserted: `PRE_ACTIVATION` remains armed; the release registry stays all-`SHADOW`; F4 rules, the A1 matrix, and the registry surface are untouched; no legacy public revival; retained v1.2 code intact (rollback remains available).

## Open blockers

The observation execution is blocked on ISSUE-0241's promotion-transition surface (the issue's blocker: no partially promoted registry is constructible today, and this epic must not construct one). Boundary contract for the blocked work: one pure, total, immutable-producing `CapabilityRegistry.withState(capability, target, state)` returning a new validated registry whose digest recomputes over its own entries, with `releaseRegistry()` byte-unchanged and no gate policy inside the surface (full contract in `capability-registry-transition-rollback-observation` §Transition-surface contract). Decomposition creates the refinement child epic that executes the pinned observation; it starts when ISSUE-0241's surface lands.

## Recorded transition-surface placement and ownership

Per capability-registry-transition-rollback-observation D1: the `PROMOTED → SHADOW` demotion surface lives inside ISSUE-0241's promotion-transition ownership as the single release-owned state transition of `CapabilityRegistry`; promotion and demotion are one surface. Applying it with `State.PROMOTED` is promotion (the registry half of ISSUE-0241's E12 release action); applying it with `State.SHADOW` is demotion (the rollback transition). Because the registry is immutable, every transition is a pure derivation returning a new validated instance — one closed cross-product rebuild covers both directions. This epic implements zero transition machinery and amends no registry code; it pins the surface's boundary contract (recorded below) and the observation that consumes it (the child-epic obligation recorded below). Construction in the observation is derivation, not amendment: promoted/demoted registry states are constructed only by derivation from `releaseRegistry()` through that surface — never by amending, reflecting, or doubling the closed registry (D2). The closed release registry contract is preserved: the private constructor stays private, `releaseRegistry()` remains the only release-owned default (all 24 entries `SHADOW`), no mutator exists, and the canonical digest derivation is unchanged.

## Recorded transition-surface boundary contract (D3)

The canonical shape every consumer (ISSUE-0241's release action, the observation) satisfies:

```text
CapabilityRegistry withState(SemanticCapability capability, Target target, State state)
    -> a new immutable CapabilityRegistry
```

Pinned invariants:

1. **Pure and total over the closed axes:** any `(capability × target × state)` combination over the closed enums is accepted; null components are rejected with the registry's existing null policy (`Objects.requireNonNull`, matching `Entry`'s compact constructor, `deal/semantic/CapabilityRegistry.java:34-39`).
2. **Immutable-producing:** the source instance (including `releaseRegistry()`/`RELEASE_DEFAULT`) is never mutated; the result is a fresh instance whose entries equal the source's except the single `(capability × target)` entry carrying the requested state.
3. **Closed-shape preservation:** the result passes the existing closed cross-product validation and pinned ordering (24 entries, S4 capability order, `LUAJIT` before `JVM`) — the rebuild reuses the existing private constructor/validation path; no new entry shape and no consumer axis (F7's two-axis pin holds).
4. **Digest recomputation:** the result's `capabilityRegistryHash()` is the SHA-256 canonical JSON digest over the result's own entries — the existing derivation, never hand-rolled.
5. **No-op idempotence:** `r.withState(c, t, r.state(c, t))` yields byte-identical entries and an equal digest.
6. **No gate policy inside the surface:** the transition is a pure state derivation. Promotion-gate enforcement (ISSUE-0241's "a promotion attempt with any gate missing is rejected") lives in ISSUE-0241's release-action/flip unit, which composes the surface — never inside it. The observation needs pure state construction; the release action needs gate policy around it.

Contract fields, carried field-for-field from capability-registry-transition-rollback-observation §Transition-surface contract:

- **Initiator:** ISSUE-0241's release action (the E12 flip unit building the promoted release registry) and the observation child epic (constructing `P`/`D`).
- **Input:** a source `CapabilityRegistry` instance; `capability`, `target`, and target `state` over the closed enums.
- **Success:** a new immutable registry whose single `(capability × target)` entry carries the requested state; the closed cross-product validation and pinned ordering pass; `capabilityRegistryHash()` is the canonical digest over the result's own entries (D3.4); the source instance and `releaseRegistry()` are byte-unchanged.
- **Visible errors:** null components rejected with the registry's existing null policy; no other error — the transition is total over the closed axes. Promotion-gate rejection is **not** a surface error: it is ISSUE-0241's release-action policy around the surface (D3.6).
- **Post-state:** source unchanged; result immutable; repeated transitions compose deterministically; a no-op transition yields byte-identical entries and an equal digest.
- **Concurrency/retry/timeout:** pure derivation; deterministic; no retry, no timeout.

## Recorded refinement child-epic obligation (E2)

Decomposition (breakdown BC-000077) creates exactly one refinement child epic — E2, ISSUE-0412 "Execute the capability-registry transition rollback observation" — per capability-registry-transition-rollback-observation D6. E2's obligation: implement the pinned observation (D4 steps 1-11 and D5 negatives) as the observation test in the gate suite, over the real planner with real facts. E2's precondition is the landed D3 surface: ISSUE-0241's promotion-transition surface must land as the D3 `withState` contract — this is the issue's blocker and the child epic's precondition; the child epic starts when ISSUE-0241's promotion-transition surface lands, and the observation executes only after that surface exists. E2's remaining blocker is assumption A2: the I3 `SIGNED_INT32` manifest claim row must be active by observation execution time; the observation asserts this fail-loud so a missing row can never silently weaken the proof. E2's gate: the observation test is green and `./run_tests.sh` exits 0 with no other expectation changes; the observation test is not merged before the D3 surface exists (its compile depends on the surface — self-enforcing).

## Design-only gate (this epic's own gate)

Per capability-registry-transition-rollback-observation Verification 5 and D6: this epic's own gate is design-only — no code change; the pinned contracts and the child-epic obligation are recorded in this epic update (the sections above). This epic writes no code at this stage.
