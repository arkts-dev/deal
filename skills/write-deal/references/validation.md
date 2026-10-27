# Validate

Use the project's verified compiler launcher, runtime and host setup:

```text
deal compile <entry.deal> --backend <luajit|jvm|js> --output <fresh-directory>
```

Inspect fresh artifacts, execute every supported backend and check expected output/errors. Compilation alone is insufficient.

| Backend | Execute |
|---|---|
| `luajit` | `luajit <entry>.lua` in the output directory |
| `jvm` | `javac --release 25 -proc:none -cp <compiler-runtime> -d <classes> <entry>.java`, then `java -cp <classes>:<compiler-runtime> <Entry>` |
| `js` | `node <entry>.js` in the output directory |

`<compiler-runtime>` is the absolute installation or checkout build directory containing runtime classes.

Entry exports synchronous main(): null. Host examples need matching implementations and an isolated manifest outside the parent project. Invoke async exports through the embedding ABI. Do not weaken source to conceal unsupported cases.

The [main example](../examples/src/main.deal) throws on mismatch. Execute [serialization](../examples/serialization/main.deal) separately; its schema requires every nonoptional field. Keep output outside worker directories and follow the existing orchestrator.

| Failure | Check |
|---|---|
| Manifest/import | Roots, declarations, wiring |
| Syntax/type | Normative grammar and signatures |
| Runtime boundary | Actual value, presence, nullish flags, bounds, ownership |
| Valid source rejected internally | Minimal compiler/backend reproduction |

Fix the first cause and rerun without weakening assertions. Distinguish compiled, executed, blocked and timed out.
