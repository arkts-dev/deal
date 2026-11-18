# Toolchain

JDK-only seam between published native artifacts and the pinned external toolchain;
no emitter/parser/orchestrator dependency. `deal.Main` drives it.

- `NativeRuntime.FILES` is the ordered deployment authority. Resolve every file
  through DistributionHome, never project-local runtime overrides. Missing files
  report `Runtime library not found: <path>`. Synchronize headers/C sources,
  unicode_case.h and release packaging. Generated registry/driver units are scratch,
  not deployment resources.
- `NativeToolchain` owns flags, LLVM 14–16 preflight, registry generation, scratch,
  linking and NativeBuildException spelling. Product builds include runtime main.c;
  driver builds omit exactly main.c and add the supplied driver. Both compile
  hosts_registry.c and link -lm after translation units.
- Scratch holds generated units only. Link to `<productPath>.partial`, then rename
  beside the caller-owned product on success. Failures preserve the old product;
  diagnostics replace staging paths with the caller's product path.
- Read publication roots without writing them. Products are independent of those
  roots; this seam publishes no compiler artifacts.
