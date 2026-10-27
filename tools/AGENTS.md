# Tools

Maintain reproducible build/test/launcher/packaging utilities. Quote paths, fail fast and avoid checkout assumptions or governance artifacts. Build the matched v1.5 runtime/stdlib set; verify packaged artifacts outside the checkout on every backend, including missing/mismatched resources and execution failures. Neither distribution script joins the dev gate.

gate-manifest.sh alone selects tests and coverage. Keep conformance checks under test/, including TypeScript syntax/grammar checks and their failure controls. Run Java suites sequentially; compile tests into build/test-classes and report production build/deal only. Enforce unrounded 85% line/75% branch minima. Distinguish measured speed from reduced work; disclose missing execution lanes. Synchronize policy with code.
