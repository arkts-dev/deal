/*
 * DEALPG4 selftest surface (stage placeholder).
 *
 * The probe batteries, selftest bound machinery, and fault-injection
 * override installation land in the selftest child; this stage keeps only
 * the mode-entry placeholder surface: MODE_NOT_IMPLEMENTED to stderr and
 * a nonzero exit, with no fork, no exec, and no channel.
 * MODE_NOT_IMPLEMENTED is a stage placeholder, not an integrity token
 * (dealpg4-launcher-core mode dispatch contract).
 *
 * Dispatch delegates probe/selftest to these entries after the
 * dispatch-owned --limit-ms shape validation and the mode-entry
 * embedded-limits ordering validation.
 */
#ifndef DEALPG4_SELFTEST_H
#define DEALPG4_SELFTEST_H

#include <stdint.h>

/*
 * probe/selftest --limit-ms floor (15000): the dispatch-owned argument
 * shape accepts at most one optional "--limit-ms N" with N a decimal
 * integer >= this floor; a smaller, non-integer, repeated, or missing
 * value is a usage error. The floor is symmetric across probe (whose
 * default bound is 15000) and selftest (whose default bound is the
 * embedded selftestLimits.selftestTimeoutMs).
 */
#define DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR 15000

/*
 * probe/selftest mode entries.
 *
 * limit_ms is the dispatch-validated --limit-ms override, or -1 when the
 * option is absent and the entry applies its mode default (probe 15000;
 * selftest = embedded selftestLimits.selftestTimeoutMs). Dispatch owns
 * the argument validation; the entries own the bound semantics. The
 * selftest child replaces these placeholder bodies in the same binary.
 */
int dealpg4_probe_entry(int64_t limit_ms);
int dealpg4_selftest_entry(int64_t limit_ms);

#endif
