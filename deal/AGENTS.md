# Compiler

Production Java supports `--release 21`; release/test tooling uses Java 25.
Preserve deterministic diagnostics and transactional publication. Lower checked
facts into validated semantic IR; backends never fall back to ASTs.

- Diagnostics retain producer-owned facts, source snapshot identities and exact
  ranges without reconstructing evidence from prose or changing validation order.
  Share repeated rule guidance; keep evidence producer-owned and advice consistent with the specification.
  JSON v2 and amendment protocol v3 are the sole current transports. Runtime
  export is opt-in, bounded and must not invoke user hooks or change Error identity.
- Private metadata owns identities/descriptors/presence/order, never user string
  keys. Normalize documented host shapes at ingress and validate complete live
  graphs before publication, preserving sharing/cycles and rollback.
- Keep binary64 specials/signed zero. Integral positions validate before access,
  never truncate. Diagnostic numbers use shared hexadecimal spelling.
- Strings retain UTF-16 units: length/order/slicing count units; iteration combines
  pairs and retains lone units. Reject malformed byte transport, not legal units;
  retain available rejecting-scanner byte positions without revisiting rejected bytes.
- Refined consumers use admitted results at the recorded read origin, not stale
  raw values. Typed boundaries retain their own projections.
- Descriptors enforce recursive data/result positions. Void/never are result-only;
  functions returning either are legal data. Never admits no normal completion. Null/undefined/absence stay distinct;
  missing reads become undefined. Required nullable fields still require presence.
- Class construction checks supplied values before publication, with omitted
  optionals absent. No initializer/default hooks or synthetic helper exports.
- Throw/catch/rethrow preserves Error identity/causes and mutable name/message.
  First throw binds diagnostic code once; later name writes cannot rewrite it.
- JSON uses exact contextual schemas, fresh typed reconstruction, extras-first
  checks and no factories. Stringify validates reached positions, preserves cycle
  identity and field presence/order, and retains E8003 leaf causes/origins.
  Records use generic omission/substitution/numeric-key rules; Error exposes only
  name/message. Finite numbers share ECMAScript spelling.
- Lua deploys internal deal/json.lua algorithms with runtime resources, never
  callable std/json wrappers. Scanner/reconstruction paths share grammar, failure
  offsets and explicit canonical targets. Preserve class plans/host identities
  across emitted/deployed runtime load orders.
