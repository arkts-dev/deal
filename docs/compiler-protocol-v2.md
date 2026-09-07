# Compiler Protocol v2

Compiler Protocol v2 is the transport-neutral boundary shared by the DEAL and Deal UI transpilers.
Canonical source remains authoritative; semantic graphs are rebuilt for every request.

## Revision And Query Contract

An inspection returns a source digest, stable declaration identities, revision-scoped node identities,
AppInterface fingerprints and structured diagnostics. A semantic query returns only the selected
declaration, body, view or subtree together with its dependencies and compiler-issued
`OperationDescriptor` values.

Each descriptor contains:

- the operation name;
- the compiler-owned target id and target kind;
- the target fingerprint at the queried revision;
- the fields required to perform that operation.

Querying a module authorizes declaration insertion without returning the complete module source.

## Checked ChangeSet

A checked ChangeSet carries the complete canonical source, `baseDigest`, target fingerprint map and
one or more semantic operations. The compiler rejects the request before candidate construction when:

- `baseDigest` does not match (`CP1001`);
- an edited target was not queried and therefore has no fingerprint (`CP1010`);
- a target fingerprint is stale (`CP1011`);
- a semantic target is unknown for the current revision (`CP1003`).

Operations are applied to a copy, fully parsed and type-checked, and committed together. Any failure
returns the original source and a structured repair scope. Node ids and fingerprints never appear in
canonical source comments and must not be persisted as application data.

## Agent Boundary

### Operation-Local Diagnostics

Structured diagnostics may include a zero-based `operationIndex` identifying the failing operation
in the response's ChangeSet. It is not a SymbolId, does not survive a reordered or new ChangeSet,
and is never stored in canonical app metadata. Repair workspace routing prioritizes this ownership
over shared module targets, since multiple declaration additions legitimately target one module.

Declaration parse failures retain lexer/parser codes, token notes and source evidence. Their ranges
and evidence refer to the submitted declaration payload, not the committed full module. CP1013 is
reserved for the one-declaration contract when parsing succeeds; syntax failure is not reported as
an unexplained declaration-count error. Accepted sibling payloads remain unchanged during repair.

Compiler Protocol v2 is intentionally rich and is not an LLM prompt format. Streaming-compiler maps
compiler ids to short, revision-local aliases and exposes only queried operation descriptors through
Agent Surface v2. The generation engine, not a model or Studio client, supplies source and target
preconditions to compiler transactions.

## Compatibility

Protocol v2 is incompatible with v1 at the agent boundary. Legacy unguarded `apply` methods remain an
internal shadow adapter during migration; production generation and refinement use checked changes.
