/*
 * DEALPG4 fault-injection seams (ISSUE-0205): hook table and production
 * defaults.
 *
 * fi.c/h is the leaf seam module (dealpg4-launcher-core D8,
 * dealpg4-probe-selftest-foundation D5):
 *  - exactly the canonical hook table struct dealpg4_fi with its three
 *    hooks: delay_ms (injected delays at named string site tags),
 *    fail (failure hooks at named int site tags), congest (congestion
 *    control at named int target/mode tags);
 *  - one process-wide hook table dealpg4_fi_hooks whose entries are the
 *    production defaults — the real behavior — linked into every mode:
 *    delay_ms is the real monotonic-bounded sleep (the requested ms
 *    against an absolute CLOCK_MONOTONIC deadline armed through the
 *    canonical timerfd machinery, EINTR recompute, no drift and no
 *    extension; it touches no deadline context, so an injected delay
 *    consumes the enclosing component's own deadline and never extends
 *    it), fail returns 0 (no failure), congest returns 0 (no
 *    congestion). Production modes behave identically to a build
 *    without seams.
 *  - overrides are installed only by selftest-child code, in-process,
 *    before scenario execution, through the installer in selftest.c/h
 *    (dealpg4_fi_install_overrides). No CLI/env key activates injection:
 *    this module reads no environment, parses no arguments, and has no
 *    external activation surface.
 *  - operational components call the hooks at their named call sites via
 *    dealpg4_fi_hooks; no operational component depends on selftest.
 *
 * Site/target catalogs are named (string/int tags); this stage ships the
 * scheme and the production defaults — no operational call sites exist
 * yet (supervisor/outer are placeholders). The children that place call
 * sites extend the catalogs (supervisor/outer fault points and
 * congestion modes land with those children).
 */
#ifndef DEALPG4_FI_H
#define DEALPG4_FI_H

/* The canonical hook table (fixed shape). Return-value convention:
 * 0 = production behavior (no injection); a nonzero return reports the
 * scripted injection (the scripted failure value or congestion mode). */
struct dealpg4_fi {
    int (*delay_ms)(unsigned ms, const char *site);   /* injected delays */
    int (*fail)(int site);                            /* failure hooks */
    int (*congest)(int target, int mode, void *arg);  /* congestion control */
};

/* The process-wide hook table. Initialized to the production defaults;
 * only the selftest-child installer (selftest.h) replaces the entries,
 * in-process, before scenario execution. */
extern struct dealpg4_fi dealpg4_fi_hooks;

/* Production defaults (the real behavior, linked into every mode):
 *  - dealpg4_fi_default_delay_ms: the real monotonic-bounded sleep for
 *    the requested ms — an absolute CLOCK_MONOTONIC deadline armed
 *    through the canonical timerfd context (timerfd_create(CLOCK_MONOTONIC,
 *    TFD_NONBLOCK|TFD_CLOEXEC)), EINTR recompute against the identical
 *    absolute deadline (no drift, no extension). It touches no deadline
 *    context: the enclosing component's own deadline fires independently
 *    during the sleep, so an injection consumes the component's deadline
 *    and never extends it. Returns 0 after the sleep, -1 on a timerfd or
 *    clock failure. The site tag is accepted and ignored (production
 *    sleeps identically at every site).
 *  - dealpg4_fi_default_fail: 0 — no failure, for any site tag.
 *  - dealpg4_fi_default_congest: 0 — no congestion, for any target/mode
 *    tag.
 */
int dealpg4_fi_default_delay_ms(unsigned ms, const char *site);
int dealpg4_fi_default_fail(int site);
int dealpg4_fi_default_congest(int target, int mode, void *arg);

#endif
