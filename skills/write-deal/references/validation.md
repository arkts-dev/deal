# Validate

Use the source checkout's compiled compiler, runtime and host setup. Run from the repository root after building. CLI shape:

```text
java -cp build deal.Main compile <entry.deal> --backend <luajit|jvm|js> --output <fresh-directory>
```

Compile through production, inspect the fresh artifact, execute it, and check expected output/errors on every supported production backend.

| Backend | Execute |
|---|---|
| `luajit` | `luajit <entry>.lua` in the output directory |
| `jvm` | `javac --release 25 -proc:none -cp <compiler-build> -d <classes> <entry>.java`, then `java -cp <classes>:<compiler-build> <Entry>` |
| `js` | `node <entry>.js` in the output directory |

`<compiler-build>` is the absolute path to the checkout's `build` directory; the JVM artifact links against its runtime classes.

Identify unsupported cases explicitly instead of weakening source: host imports need an embedding implementation; async exports are invoked through the embedding's ABI, not by a plain run; an entry must export synchronous `main(): null`.

[Example entry](../examples/src/main.deal) throws on mismatch and otherwise completes silently. Host binding needs an embedding implementation; copy its manifest outside the parent project. Serialization has its own executable entry and requires every nonoptional field; validate it separately as well as the main example. Keep output outside active worker directories; follow the existing orchestrator.

| Failure | Check |
|---|---|
| Manifest/import | Roots, declarations, wiring |
| Syntax/type | Normative grammar and signatures |
| Runtime boundary | Actual value, presence, null, bounds, ownership |
| Valid source rejected internally | Minimal compiler/backend reproduction |

Fix the first causal failure; rerun the same check with diagnostics and required behavior intact. Distinguish compiled, executed, blocked, and timed out.
