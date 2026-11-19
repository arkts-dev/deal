# JVM Backend

`JvmSemanticEmitter`/`JvmNames` render validated IR only. Verify real javac/java
execution, host/async identity and normalized failures.

- Distinguish null, missing and UNDEFINED; resultless bodies return UNDEFINED.
  Async-export result parsing admits void only in result positions.
- Function carriers save/restore activation slots on every invocation path;
  restore cell references without undoing live captured mutations. Segment whole
  operations; finally preserves recursion, transfers, frames and diagnostics.
- Partition large closures/factories/snapshots into nested classes to avoid
  constant-pool limits. Preserve qualification, initialization/maps and re-entry.
  Generated loop/catch temporaries use operation-specific names, including lambdas.
- Preserve host aliases/descriptors and cache bridges. Admit complete current
  class/Record/unknown graphs with operation-local identity/descriptor guards,
  including unread callable bodies/signatures. Closed malformed-transport reasons
  replace blanket catches; no persistent validity certificates.
- Contextual JSON uses the shared stack-safe scanner/reconstructor and each
  artifact's immutable exact plan map, never process-global schema selection.
  Preserve private field order/presence/cycle identity and generic Record rules.
  Error exposes mutable required name/message; first throw binds code independently.
  Host rejection producers retain known crossing/signature/declaration facts;
  propagated host Errors acquire no inferred blame.
- Reuse StringSemantics. Escape UTF-16 units losslessly in traces; console UTF-8
  display replaces lone units only, without changing language text.
