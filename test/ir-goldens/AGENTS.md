# IR Goldens

Maintain deterministic fixtures for the legacy typed-AST observer. Preserve fixture/golden pairing and regenerate only through the supported update path after every fixture passes parsing and checking. Fixtures use Promise results, inferred for-of bindings, safe unknown catch/Record extraction and explicit null data returns; resultless void controls remain distinct. Null result boundaries are observed, while void has no data-return boundary. Do not establish these dumps as the semantic-IR backend contract. Keep this file always synchronized with code.
