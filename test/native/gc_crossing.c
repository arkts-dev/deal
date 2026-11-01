/*
 * test/native/gc_crossing.c — the host implementation unit of the
 * "host/gccross" declaration module (`llvm-native-memory-management` M5,
 * `llvm-native-conformance-gate` G6, `llvm-native-host-abi-and-async` H2/H3).
 *
 * The native stress suite uses this unit for four lifetimes that generated code
 * alone cannot construct:
 *
 *   - `makeHolder` builds a class instance carrying a nested string array and a
 *     host-built record (table) with the boxed construction surface, so the
 *     return crossing materializes a host-built class/table/array graph that
 *     DEAL then owns; one record member aliases the class's own array carrier,
 *     so the crossing's alias-identity preservation and the table carrier's
 *     own rooting are both observable after full collections (R11, M5);
 *   - `retain`/`retainedLabel`/`release` convert a crossing value to an opaque
 *     handle and read it back after the suite's full collections, so the handle
 *     table's root is what keeps the value alive;
 *   - `swallow` applies a DEAL function and discards the carried DEAL-origin
 *     failure token, returning its own value (the `pcall` spelling);
 *   - `relay` applies a DEAL function and returns the unchanged record, so the
 *     runtime re-admits the identical Error.
 *
 * The unit includes only its generated declaration header plus the runtime
 * headers it uses, and defines the registration record and the declared
 * metadata exactly once.
 */
#include "host/gccross.h"

#include <string.h>

static deal_handle retained_handle;
static int retained_live;

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
    return failure_result("E_HOST_GCCROSS", "null carrier construction failed");
  }
  return ok_result(carrier);
}

static deal_host_result number_result(double value) {
  deal_value carrier;
  if (deal_number(value, &carrier) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS",
                          "number carrier construction failed");
  }
  return ok_result(carrier);
}

/* Builds the record member of the host graph: an insertion-ordered table whose
 * members are a number, a string, the class's own tag array (one managed array,
 * two references to it) and a nested table. */
static deal_host_result build_record(deal_value tags, deal_value *out) {
  deal_value record;
  if (deal_table_new(&record) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "record construction failed");
  }
  {
    deal_value three;
    if (deal_number(3.0, &three) != DEAL_OK
        || deal_table_set(record, "alpha", 5u, three) != DEAL_OK) {
      return failure_result("E_HOST_GCCROSS", "record number member failed");
    }
  }
  {
    deal_value two;
    if (deal_string_new("two", 3u, &two) != DEAL_OK
        || deal_table_set(record, "beta", 4u, two) != DEAL_OK) {
      return failure_result("E_HOST_GCCROSS", "record string member failed");
    }
  }
  if (deal_table_set(record, "tags", 4u, tags) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "record alias member failed");
  }
  {
    deal_value nested;
    deal_value deep;
    if (deal_table_new(&nested) != DEAL_OK
        || deal_number(1.0, &deep) != DEAL_OK
        || deal_table_set(nested, "deep", 4u, deep) != DEAL_OK
        || deal_table_set(record, "nested", 6u, nested) != DEAL_OK) {
      return failure_result("E_HOST_GCCROSS", "record nested member failed");
    }
  }
  *out = record;
  return ok_result(record);
}

/* One host-built class instance with a nested string array and record. */
static deal_host_result make_holder_impl(const deal_value *args, size_t argc) {
  const void *bytes = NULL;
  size_t length = 0;
  if (argc != 1u || deal_as_string(args[0], &bytes, &length) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "makeHolder takes one string");
  }
  deal_value tags;
  if (deal_array_new_typed(2, "string", &tags) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "array construction failed");
  }
  static const char *const tag_texts[] = {"alpha", "beta"};
  for (int64_t index = 0; index < 2; index += 1) {
    deal_value element;
    if (deal_string_new(tag_texts[index], strlen(tag_texts[index]), &element)
            != DEAL_OK
        || deal_array_set(tags, index, element) != DEAL_OK) {
      return failure_result("E_HOST_GCCROSS", "array element store failed");
    }
  }
  deal_value holder;
  if (deal_host_gccross_Holder_new(&holder) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "class construction failed");
  }
  deal_value label;
  if (deal_string_new(bytes, length, &label) != DEAL_OK
      || deal_host_gccross_Holder_label_set(holder, label) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "label field store failed");
  }
  deal_value count;
  if (deal_number(2.0, &count) != DEAL_OK
      || deal_host_gccross_Holder_count_set(holder, count) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "count field store failed");
  }
  if (deal_host_gccross_Holder_tags_set(holder, tags) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "tags field store failed");
  }
  deal_value record;
  deal_host_result built = build_record(tags, &record);
  if (built.failed) {
    return built;
  }
  if (deal_host_gccross_Holder_record_set(holder, record) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "record field store failed");
  }
  return ok_result(holder);
}

static deal_host_result retain_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return failure_result("E_HOST_GCCROSS", "retain takes one value");
  }
  if (retained_live) {
    if (deal_release(retained_handle) != DEAL_OK) {
      return failure_result("E_HOST_GCCROSS", "stale release failed");
    }
    retained_live = 0;
  }
  if (deal_retain(args[0], &retained_handle) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "retain failed");
  }
  retained_live = 1;
  return null_result();
}

static deal_host_result retained_label_impl(const deal_value *args,
                                            size_t argc) {
  (void)args;
  (void)argc;
  if (!retained_live) {
    return failure_result("E_HOST_GCCROSS", "no retained handle");
  }
  deal_value holder;
  if (deal_handle_value(retained_handle, DEAL_HOST_CLASS_ATOM_GCCROSS_HOLDER,
                        &holder) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "handle value lookup failed");
  }
  deal_value label;
  if (deal_host_gccross_Holder_label_get(holder, &label) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "label field read failed");
  }
  const void *bytes = NULL;
  size_t length = 0;
  if (deal_as_string(label, &bytes, &length) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "label is not a string");
  }
  deal_value text;
  if (deal_string_new(bytes, length, &text) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "string copy failed");
  }
  return ok_result(text);
}

static deal_host_result release_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  if (retained_live) {
    if (deal_release(retained_handle) != DEAL_OK) {
      return failure_result("E_HOST_GCCROSS", "release failed");
    }
    retained_live = 0;
  }
  return null_result();
}

/* Applies the DEAL function and discards a carried DEAL-origin failure. */
static deal_host_result swallow_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return failure_result("E_HOST_GCCROSS", "swallow takes one function");
  }
  deal_host_result applied;
  if (deal_apply(args[0], NULL, 0u, &applied) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "apply failed");
  }
  if (applied.failed) {
    if (applied.failure.kind != DEAL_FAILURE_DEAL_ERROR) {
      return failure_result("E_HOST_GCCROSS", "swallow expects a DEAL failure");
    }
    if (deal_failure_discard(applied.failure.error) != DEAL_OK) {
      return failure_result("E_HOST_GCCROSS", "discard failed");
    }
  }
  return number_result(7.0);
}

/* Applies the DEAL function and relays the unchanged result record. */
static deal_host_result relay_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return failure_result("E_HOST_GCCROSS", "relay takes one function");
  }
  deal_host_result applied;
  if (deal_apply(args[0], NULL, 0u, &applied) != DEAL_OK) {
    return failure_result("E_HOST_GCCROSS", "apply failed");
  }
  return applied;
}

const deal_host_field deal_host_fields_gccross_Holder[] = {
    {"label", "string", 0},
    {"count", "number", 0},
    {"tags", "[string]", 0},
    {"record", "record", 0},
};

/* The module's declared class records; a class export entry points at one of
 * them (H2's registration consistency rule). */
const deal_host_class deal_host_classes_gccross[] = {
    {DEAL_HOST_CLASS_ATOM_GCCROSS_HOLDER, deal_host_fields_gccross_Holder,
     sizeof deal_host_fields_gccross_Holder
         / sizeof deal_host_fields_gccross_Holder[0]},
};

/* The single named record the generated header declares. */
const deal_host_class deal_host_class_gccross_Holder = {
    DEAL_HOST_CLASS_ATOM_GCCROSS_HOLDER, deal_host_fields_gccross_Holder,
    sizeof deal_host_fields_gccross_Holder
        / sizeof deal_host_fields_gccross_Holder[0]};

const deal_host_export deal_host_exports_gccross[] = {
    {"Holder", "@$external/host/gccross/Holder", DEAL_HOST_EXPORT_CLASS, 0,
     NULL, NULL, &deal_host_classes_gccross[0]},
    {"makeHolder", "(string)->@$external/host/gccross/Holder",
     DEAL_HOST_EXPORT_FUNCTION, 0, make_holder_impl, NULL, NULL},
    {"retain", "(@$external/host/gccross/Holder)->null",
     DEAL_HOST_EXPORT_FUNCTION, 0, retain_impl, NULL, NULL},
    {"retainedLabel", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0,
     retained_label_impl, NULL, NULL},
    {"release", "()->null", DEAL_HOST_EXPORT_FUNCTION, 0, release_impl, NULL,
     NULL},
    {"swallow", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
     swallow_impl, NULL, NULL},
    {"relay", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, relay_impl,
     NULL, NULL},
};

const deal_host_module deal_host_module_gccross = {
    DEAL_HOST_SPECIFIER_GCCROSS, deal_host_exports_gccross,
    sizeof deal_host_exports_gccross / sizeof deal_host_exports_gccross[0],
    deal_host_classes_gccross,
    sizeof deal_host_classes_gccross / sizeof deal_host_classes_gccross[0],
    NULL};
