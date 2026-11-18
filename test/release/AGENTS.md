# Release Tests

`tools/verify-release.sh` executes the committed smoke project outside the checkout;
no development gate invokes it. Use the active manifest version with no local stdlib/
runtime copies or source-tree/classpath leakage.

Assert real JS/LuaJIT/JVM and native O0/O2 execution: binary64, required-nullable
construction, absent optionals, contextual JSON/stringify and failed reconstruction
with unchanged destination. Test artifacts/behavior, not implementation internals.
