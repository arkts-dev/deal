/*
 * test/native/gc_control.c — the host implementation unit of the
 * "host/gc" declaration module (`llvm-native-memory-management` M7,
 * `llvm-native-conformance-gate` G6).
 *
 * The native stress suite drives collection timing from inside a DEAL
 * activation through this unit: `collect()` runs one complete incremental cycle
 * (including the atomic phase and the sweep), and the three accessors expose the
 * runtime's own statistics so a program can assert that a fully live heap
 * reclaims nothing and that a dropped cycle is reclaimed.
 *
 * Every entry uses the boxed host surface of `llvm-native-host-abi-and-async`
 * H2: a `null` result is the `deal_null` carrier and a `number` result the
 * `deal_number` carrier. The unit includes only its generated declaration
 * header, which reaches `deal_rt.h`, plus the runtime's collector header that
 * declares the two observation entries.
 */
#include "host/gc.h"

#include "deal/native/memory.h"

static deal_host_result ok_result(deal_value value) {
  deal_host_result result;
  result.failed = 0;
  result.value = value;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = NULL;
  result.failure.message = NULL;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0u;
  return result;
}

static deal_host_result failure_result(const char *code, const char *message) {
  deal_host_result result;
  result.failed = 1;
  result.value.slot = 0u;
  result.value.generation = 0u;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = code;
  result.failure.message = message;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0u;
  return result;
}

static deal_host_result null_result(void) {
  deal_value carrier;
  if (deal_null(&carrier) != DEAL_OK) {
    return failure_result("E_HOST_GC", "null carrier construction failed");
  }
  return ok_result(carrier);
}

static deal_host_result number_result(double value) {
  deal_value carrier;
  if (deal_number(value, &carrier) != DEAL_OK) {
    return failure_result("E_HOST_GC", "number carrier construction failed");
  }
  return ok_result(carrier);
}

static deal_host_result collect_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_gc_collect_full();
  return null_result();
}

static deal_host_result swept_impl(const deal_value *args, size_t argc) {
  deal_gc_statistics_record record;
  (void)args;
  (void)argc;
  deal_gc_statistics(&record);
  return number_result((double)record.swept_objects);
}

static deal_host_result collections_impl(const deal_value *args, size_t argc) {
  deal_gc_statistics_record record;
  (void)args;
  (void)argc;
  deal_gc_statistics(&record);
  return number_result((double)record.collections);
}

static deal_host_result allocated_impl(const deal_value *args, size_t argc) {
  deal_gc_statistics_record record;
  (void)args;
  (void)argc;
  deal_gc_statistics(&record);
  return number_result((double)record.allocated_bytes);
}

const deal_host_export deal_host_exports_gc[] = {
    {"collect", "()->null", DEAL_HOST_EXPORT_FUNCTION, 0, collect_impl, NULL,
     NULL},
    {"sweptObjects", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, swept_impl,
     NULL, NULL},
    {"collections", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     collections_impl, NULL, NULL},
    {"allocatedBytes", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     allocated_impl, NULL, NULL},
};

const deal_host_module deal_host_module_gc = {
    DEAL_HOST_SPECIFIER_GC, deal_host_exports_gc,
    sizeof deal_host_exports_gc / sizeof deal_host_exports_gc[0], NULL, 0u, NULL};
