# AST

Model parsed source syntax and exact spans, not execution policy. Keep nodes target-neutral and structurally stable. A declaration identifier that a diagnostic anchors on carries its own exact span beside the containing node's span. Update parser, visitors, checker, lowering, and tests together. Never establish AST as a permanent backend interface. Project manifests alone own language versions; source has no version directive or version AST metadata. Keep this file always synchronized with code.
