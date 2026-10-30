# Tools

Maintain reproducible build/test/launcher/packaging. Quote paths, fail fast and avoid checkout assumptions or governance artifacts. Build the matched v1.5 runtime/stdlib set, including the complete C11 runtime deployment set that ships byte-equal to its checkout files; verify packaged artifacts outside the checkout on every backend, including missing/mismatched resources and execution failures. Neither distribution script joins the dev gate.

gate-manifest.sh alone selects tests and coverage. Keep conformance checks under test/, including TypeScript syntax/grammar checks and their failure controls. Bound concurrency; lock batched native objects per-gate; retain cold-controls. Tests: build/test-classes; production coverage: build/deal. Enforce unrounded 85% line/75% branch minima. Distinguish measured speed from reduced work; disclose missing execution lanes. Synchronize policy and code.
