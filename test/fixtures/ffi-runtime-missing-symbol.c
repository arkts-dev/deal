/*
 * ffi-runtime-missing-symbol.c — minimal non-DEAL C99 support library
 * for the ISSUE-0165 C FFI classification battery: a loadable shared
 * object that deliberately does NOT export the symbol the record's
 * declaration pins (fixture_missing_add). The production load_ffi opens
 * the library, resolves every declared symbol through the retained
 * handle, and the absent symbol raises the cached FFI_SYMBOL_MISSING.
 *
 * Includes only ISO C99 standard headers — no DEAL headers, no
 * generated support.
 */

#include <stdint.h>

/* An unrelated export proves the library itself is valid and loadable;
 * it is never declared on the DEAL side. */
int fixture_unrelated_export(void)
{
    return 0;
}
