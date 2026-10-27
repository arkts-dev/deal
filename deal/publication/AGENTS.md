# Publication

Publish whole-project artifacts transactionally: stage, validate, then atomically replace. Never expose mixed generations or partial output. Preserve per-root locking and crash recovery; temporary nonces never enter artifact bytes. Runtime copies read checkout files; stdlib copies prefer project-local overrides. No distribution-home or classpath discovery. Exercise failure and restoration paths. Keep this file always synchronized with code.
