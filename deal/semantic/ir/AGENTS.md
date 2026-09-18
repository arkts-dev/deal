# Semantic IR

Define the validated, executable, backend-neutral compiler contract. Admit no AST or checker-owned objects; keep IDs, descriptors, payloads, ordering, and serialization deterministic. Evolve lowerer, validator, Oracle, every emitter, and tests atomically. Ultimate goal: all production backends consume this IR, retiring AST-consuming emitters. Architectural direction never expands assigned task scope. Keep this file always synchronized with code.
