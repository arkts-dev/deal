package deal.semantic.ir;

/**
 * The closed module-import kind set of {@code MODULE_IMPORT} (parent
 * closed operation table): the resolved import is a compiled DEAL module,
 * a standard-library module, or a host module.
 *
 * <p>Closed set — exactly {@link #COMPILED}, {@link #STDLIB}, and
 * {@link #HOST}; no open or unknown fallback member and no external
 * extension point exist. {@code MODULE_IMPORT} initializes/loads once; a
 * cycle is frontend E2005 and host load failures use E8011
 * ({@code HOST_LOAD}).</p>
 */
public enum ModuleImportKind {

    /** A compiled DEAL implementation module. */
    COMPILED,

    /** A standard-library module (spec-stdlib). */
    STDLIB,

    /** A host module loaded at import time. */
    HOST
}
