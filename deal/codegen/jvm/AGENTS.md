# JVM Backend

Emit valid JVM artifacts from validated semantic IR and keep runtime identity, host, async, and boundary behavior exact. Extend JvmSemanticEmitter for semantics; never restore AST emission. Keep the shared JvmNames naming surface single-sourced. Verify Oracle parity and real javac/java execution. Numbers and raw array-slot trace indexes use binary64; slot atoms use canonical hexadecimal spelling, never boxed-kind or decimal-format inference. Keep this file always synchronized with code.
