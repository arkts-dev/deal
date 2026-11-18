# Frontend Conformance

Test lexer/parser/checker/resolution independently of execution. Pin acceptance,
exact diagnostics and token ranges; companions stay subordinate.

- Comments are whitespace: retain range/line-break controls, recursive declarations,
  unused annotation errors and absent synthetic symbols. Reject $ universally.
- Distinguish RHS read facts from fixed writes; direct fields refine only their
  owning binding, not aliases. Contextual parameters/literals preserve exact targets.
- Record keys admit quoted/escaped/computed strings under genuine Record context.
  Supply required class/Error fields so omissions cannot mask intended failures.
- Use Promise results, inferred for-of bindings and checked unknown extraction.
  Reject namespace data/array deletion; accept contextual identifiers.
- Keep retired-syntax and TypeScript-subset negatives: malformed templates, bare
  function expressions, constructor fields, adjacent minus tokens and invalid
  nested declaration names/collisions.
