# Validate

Use the project's verified compiler launcher, runtime, and host setup. Inspect help before adding flags. CLI shape:

```text
deal compile <entry.deal> --backend <luajit|jvm|js> --output <fresh-directory>
```

Compile through production, inspect the fresh artifact, execute it, and check expected output/errors on every supported production backend. Identify unsupported cases explicitly. Use each backend's documented launcher and runtime dependencies.

[Example entry](../examples/src/main.deal) throws on mismatch and otherwise completes silently. Host binding needs an embedding implementation; copy its manifest outside the parent project. Serialization is a separate compiler-support probe, not part of that entry. Keep output outside active worker directories; follow the existing orchestrator.

| Failure | Check |
|---|---|
| Manifest/import | Roots, declarations, wiring |
| Syntax/type | Normative grammar and signatures |
| Runtime boundary | Actual value, presence, null, bounds, ownership |
| Valid source rejected internally | Minimal compiler/backend reproduction |

Fix the first causal failure; rerun the same check with diagnostics and required behavior intact. Distinguish compiled, executed, blocked, and timed out.
