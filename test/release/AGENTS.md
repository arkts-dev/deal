# Release Tests

The committed smoke project is the out-of-checkout discovery and execution subject for `tools/verify-release.sh`: keep its manifest on the sole active v1.3 version, its source free of any project-local `std/` or runtime copy, and its output checking real compiled artifacts. Prevent source-tree or classpath leakage; assert real artifacts and observable execution, not implementation internals. Distribution verification belongs to the tools script, and no dev gate invokes it. Keep this file always synchronized with code.
