# Parser

Translate tokens into AST according to the product specification. Preserve scalar-correct ranges, directives, and deterministic recovery. Every declaration identifier a checker diagnostic anchors on carries its own token span in addition to the containing node's span (the catch variable is the statement-local case). Keep checker and backend policy outside parsing. Add focused accepted and rejected fixtures for grammar changes. Project manifests alone own language versions; source has no version directive or version AST metadata. Keep this file always synchronized with code.
