# Project Configuration

Own strict manifest parsing, project discovery, roots, outputs, and external declarations. Validate before overrides or output creation. Normalize paths once while preserving configured identity where required. Require languageVersion exactly "1.4" and stdlib exactly "1.4" (the default when absent); reject older manifest versions before publication. Source/declaration directives cannot select a version. Configuration failures remain deterministic and publish nothing. Keep this file always synchronized with code.
