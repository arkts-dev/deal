# Tools

Keep build/test/launch/package workflows reproducible. Quote paths, fail fast and
avoid checkout assumptions. Distribution scripts stay outside the development gate.

- Package matched runtime/stdlib resources, including internal deal/json.lua and
  the byte-identical closed C11 set. Callable modules: console/string/table/math/time,
  never std/json. Verify outside the checkout on every backend with real executions
  and missing/mismatched-resource negatives.
- `generate-unicode-case.py` checks pinned Unicode 17.0 input hashes and regenerates
  backend-local mappings/context properties with license notices. Ship unicode_case.h.
- `gate-manifest.sh` alone selects tests/coverage. Native O0/O2 conformance/accounting
  and compositions have no exclusions; missing hosts/requirements fail. Preserve
  full deep-JSON success/failure matrices and common JS/LuaJIT/JVM execution.
- Keep conformance/TypeScript grammar checks under test/. Bound suite concurrency,
  lock batched native objects per gate and retain cold/cache-invalidating controls.
- Tests compile to build/test-classes; coverage counts build/deal only and enforces
  unrounded 85% line/75% branch minima. Stateful tests require Python/Hypothesis from
  test/requirements.txt; missing dependencies fail, never skip.
