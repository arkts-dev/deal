# JVM Backend

Emit valid JVM artifacts from validated semantic IR and keep runtime identity, host, async, and boundary behavior exact. Extend JvmSemanticEmitter for semantics; the retained AST-walking emitter lives in the test scope and is never a production path. Keep the shared JvmNames naming surface single-sourced. Verify Oracle parity and real javac/java execution. Keep this file always synchronized with code.
