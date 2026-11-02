# Lexer

Produce tokens, directives, and exact Unicode-scalar spans with deterministic recovery. Delegate position arithmetic to source utilities. Encode syntax only; TemplateText trailing backslashes pair; reject malformed TypeScript numeric/hex/Unicode escape spellings without decoding valid raw text. Adjacent minus characters reject rather than becoming two operators. Update lexer, parser, and diagnostic tests together. Project manifests alone own language versions; source has no version directive or version AST metadata. Keep policy synchronized.
