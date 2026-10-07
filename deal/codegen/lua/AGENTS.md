# LuaJIT Backend

Emit LuaJIT artifacts from validated semantic IR while preserving Lua ABI, FFI, async, errors, and evaluation order. Extend LuaSemanticEmitter for semantics; the retained AST-walking emitter lives in the test scope and is never a production path. Verify Oracle parity and real luajit execution. Keep this file always synchronized with code.
