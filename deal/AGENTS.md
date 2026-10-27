# Compiler

Own compiler phases, runtimes and CLI integration. Preserve deterministic diagnostics and transactional publication. Lower checked facts into validated semantic IR; every backend renders that IR without AST fallback.

- Use private carrier metadata; user string keys cannot forge runtime identities. Normalize documented host shapes at ingress.
- Preserve binary64 values, signed zero, NaN and infinities. Integral positions validate before access, never truncate. Render diagnostic numbers through the shared hexadecimal formatter; `int` and `number` are ordinary identifiers.
- Validate call-preserved refinements at consumption using the read's checked descriptor and origin. Consumers use admitted operands, never stale raw values; typed boundaries retain their own failure projections.
- Class construction uses supplied values in declaration order. Required-nullable fields require presence; omitted optionals remain absent. Never consult defaults hooks.
- Null, undefined and missing presence remain distinct. Missing reads normalize to undefined, not null. Sync host no-result admits void only.
- Throw/catch/rethrow preserve Error identity, mutable code/message, original diagnostic facts and linked causes. Error held as unknown remains an error trace atom.
- Keep generic stringify, declared-field encoding and dynamic Record-content validation separate. Generic JSON omits object undefined/functions and substitutes null in arrays/nonfinite positions; declared numbers substitute null; dynamic Record walkers reject unsupported leaves at their own paths.
- Class JSON reads private declaration-order storage, respects presence and detects cycles by original identity. Error exposes code/message only. Preserve metadata across host copies and both Lua runtime load orders.

Keep implementation and affected policies synchronized; place regression details in executable tests, not policy inventories.
