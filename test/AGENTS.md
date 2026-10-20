# Tests

Assert product behavior, regressions, and production paths—not governance state. Prefer minimal deterministic fixtures and exact failures. New semantics require lowerer, validator, Oracle, and backend parity coverage. Retire legacy-only assertions with AST emitters. Run focused tests first. Backend control-flow regressions execute emitted code and cover unmatched fall-through as well as taken branches. Architectural direction never expands assigned task scope. Keep this file always synchronized with code.
