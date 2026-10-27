/*
 * deal/native/host.h — the `host` service group.
 *
 * The native host load surface and the crossing machinery
 * (`llvm-native-host-abi-and-async` H1, H2, H4, H7). The host-visible value
 * surface itself is H2's canonical declaration set in `deal_rt.h`; this header
 * carries the two records the emitted module hands to the runtime loader and the
 * one entry the loader implements.
 *
 * Load contract (H1, H7, `LoadHostModule`): at module initialization the
 * importing unit passes its generated declared-module record and the origin of
 * the importing statement. The runtime resolves the raw specifier against the
 * compiled registry (`deal_host_registry`/`deal_host_registry_count`), validates
 * the declared export set, kinds, descriptors and class metadata, and answers
 * one validated binding whose members are all H2-declared types. The importing
 * unit's emitted walk builds and publishes that import's own `deal_module`
 * export table from the binding; the runtime never exposes a module layout
 * (C2/C3/H6 opacity). A declared and imported host module whose implementation
 * unit was not supplied has no registry entry and fails here with the closed
 * E8011 `HOST_LOAD_FAILED` arm at the importing origin.
 *
 * The binding is platform memory created once at module initialization, holds
 * no managed pointer, and lives for the process lifetime; allocation failure is
 * the R10 resource fault. The crossing entries, the generated host headers, the
 * handle table and the error-token table land with the host-sync child, which
 * extends this header.
 */
#ifndef DEAL_NATIVE_HOST_H
#define DEAL_NATIVE_HOST_H

#include "deal/native/deal_rt.h"

/*
 * One generated declared-module record: the raw import specifier, the
 * declaration module's module-id path, the declared exports in declaration
 * order (names, canonical descriptors and kinds; inactive implementation
 * pointers are NULL) and the declared class metadata. Emitted as static data by
 * the importing unit's module walk.
 */
typedef struct {
  const char *specifier;               /* raw specifier, e.g. "host/http" */
  const char *module_path;             /* the declaration module's module-id path */
  const deal_host_export *exports;     /* declared exports, declaration order */
  size_t export_count;
  const deal_host_class *classes;      /* declared class metadata */
  size_t class_count;
} deal_host_declared_module;

/*
 * The runtime-known validated binding of one loaded host module: the raw
 * specifier, the validated registration and one validated export record per
 * declared export, in declaration order. It contains no `deal_module` and no
 * managed pointer.
 */
typedef struct {
  const char *specifier;
  const deal_host_module *module;
  const deal_host_export **exports;
  size_t export_count;
} deal_host_binding;

/* The native host load entry (H1, H7); implemented by the host-sync child. */
deal_outcome deal_host_load(const deal_host_declared_module *declared,
                            const deal_origin *origin,
                            const deal_host_binding **out);

#endif /* DEAL_NATIVE_HOST_H */
