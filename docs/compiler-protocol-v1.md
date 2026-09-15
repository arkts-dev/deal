# Compiler Protocol v1

## Boundary

The shared protocol is a platform-neutral, stateless boundary exposed by the DEAL transpiler and
framework frontends to streaming-compiler.
Canonical source is the only durable program representation. Every request is independently
executable from source bytes, compiler inputs and an expected SHA-256 digest.

Inspection returns semantic declarations and revision-scoped node handles. A handle is valid only
with the digest that issued it. Clients must not reconstruct handles from source text or persist them
as application metadata.

## Transactions

An edit transaction contains one or more compiler-defined operations. The frontend reparses the base
source, verifies its digest, applies operations to a private candidate, validates the complete
candidate and returns canonical source plus an impact report. Any stale handle, invalid operation or
compiler diagnostic rejects the entire transaction and leaves the base source unchanged.

The first DEAL frontend operations are declaration addition/removal/rename, complete function-body
replacement, block-body replacement and expression replacement. Deal UI adds view/subtree
replacement, child insertion/removal/move and typed property/action updates.

## Diagnostics

Structured diagnostics carry a stable code, severity, source range, owner symbol/node, related ids,
expected and actual facts, allowed repair operations and the minimum context query. The upstream
transpilers return these facts without LLM policy. Streaming-compiler derives model-facing tool
schemas, context slices and repair rounds from them. Provider transport failures, generation budgets
and retry policy are outside this protocol.

## Ownership

DEAL and Deal UI remain transpilers and semantic checkers. They parse, inspect, apply atomic source
edits, type-check and project canonical sources. They do not call model APIs, stream tool arguments,
choose providers, construct prompts or retain generation sessions. Those responsibilities belong to
the independent streaming-compiler service/library. Product clients call streaming-compiler and may
use the transpiler protocol directly only for deterministic inspection, compilation and rendering.

## Cross-artifact Compilation

DEAL inspection emits an AppInterface snapshot and fingerprints for public declarations, root state,
actions, handlers, effects and capabilities. Deal UI consumes that snapshot. Canonical application
compilation validates both sources and reports whether a DEAL change invalidates UI bindings or
requires resetting runtime state. Version 1 resets state whenever the root-state schema changes.
