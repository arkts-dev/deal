# DEAL Compiler Repository Guidance

## Product Boundary

This repository owns the platform-neutral DEAL transpiler, semantic checker and deterministic edit
API. It must not depend on Android, Compose, DEAL Studio, streaming-compiler, model providers,
prompts, application scenarios or generated-app product policy. The edit API is compiler
infrastructure consumed by streaming-compiler; it does not contact an LLM or run generation rounds.

## Canonical Source And Workspaces

DEAL source is authoritative. Compiler graphs and edit workspaces are ephemeral and must be fully
reconstructable from source in every request. Public edit operations are stateless: they carry the
complete source, an expected source digest and compiler-issued revision-scoped handles.

- Qualified declaration identities may remain stable while their names remain stable.
- Node identities are opaque and valid only for the source digest that produced them.
- Never persist node ids in source comments or require clients to infer ids from source text.
- Candidate edits use copy, validate, project semantics. A rejected edit returns the original source.
- Public DTOs and diagnostics are platform-neutral Java records with a versioned canonical JSON form.
- Repair workspaces preserve valid payloads as sealed slots. Directed declaration references form a
  dependency DAG; mutually dependent operations form one strongly connected group.
- A frontend may attach a deterministic candidate validator, but it must run inside the same atomic
  stage/patch lifecycle rather than creating a second repair path.

## Language And Framework Separation

Core DEAL remains a mutable TypeScript-shaped language. Framework-specific borrowed-value rules,
UI component contracts and Studio acceptance requirements do not change core DEAL semantics.
Shared protocol types may be reused by frontends, but frontend policy remains in its owning compiler.

## Generalization

Compiler behavior must depend only on grammar, types, effects, capabilities, semantic identities and
declared framework contracts. Do not add scenario names, prompt vocabulary, application blueprints,
model-specific repair rules or Android host behavior to production compiler code.

Stable diagnostics include a code, source range, owning semantic id, expected/actual facts and the
allowed edit scopes. Streaming-compiler turns these facts into model tools and repair context; the
DEAL transpiler never owns prompts, tool-call transport, retry budgets or model-facing state.
