# Distribution

Resolve packaged runtimes and standard-library resources through explicit deterministic tiers. Keep resolution read-only, low-level, and free of hidden working-directory assumptions. Preserve absence behavior and test each supported location. Discover only the five callable stdlib modules, never std/json. Internal deal/json.lua resolves as a runtime resource without project overrides. Keep this file always synchronized with code.
