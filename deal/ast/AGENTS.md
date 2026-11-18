# AST

Model target-neutral syntax and exact spans, not execution policy or backend APIs.
Update parser, visitors, checker and lowering together.

- Preserve let/const binding kind; const does not imply deep immutability.
  Diagnostic declaration identifiers retain their own token spans.
- Function expressions include arrows and optional parameter annotations; retain
  expression-body form for void checks while lowering execution as ordinary blocks.
- Conditionals keep distinct condition/arm nodes. Optional guards mark receivers/
  callees and completed wrappers own postfix chains; parentheses separate chains.
- Properties distinguish decoded static names from computed keys; visit keys before
  values. Classes carry name, fields and spans; fields have type/presence only.
- Comments are whitespace, never directives, schemas or synthetic exports. Manifests
  alone select language versions; no version/initializer AST metadata.
