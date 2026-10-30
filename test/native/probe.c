/*
 * test/native/host_probe.c — the host implementation unit of the
 * "host/probe" declaration module.
 *
 * It is a real host unit: it includes only the generated declaration header
 * `host/probe.h` (published beside the project module and reached through the
 * build seam's published-root include path) and defines the registration record
 * exactly once, so a link of a registered product proves that the generated
 * header is the complete declaration surface a host consumes
 * (`llvm-native-host-abi-and-async` H1/H2).
 *
 * DEAL_HOST_PROBE_CASE selects the behavior under test; the default unit is a
 * conforming implementation.
 *
 *   0  full declared surface: sum, makeConfig, makeArray,
 *      makeWrongElementArray, makeError, hostTextFailure, handleProbe,
 *      applyRelay, applyDiscard, noop, makeHolder, makeEmpty, echo,
 *      malformedText, makeSparseArray, makeRecord, makeCycle, applyGraph,
 *      makeNullish, pickNullish, nullishList, absentFieldGraph, innerValue,
 *      makeInner, wrongInnerKind, wrongInnerSignature, makeUntyped, applyOne,
 *      applyUnknownResult, untypedArgument, makeBrokenError,
 *      classAtomStatuses
 *   1  a declared export is missing (prefix removal is done by the driver)
 *   2  an extra export that the declaration does not name (ignored)
 *   3  a class record carrying a duplicate field (invalid metadata)
 *   4  a class identity that differs from the declaration
 *   5  a present-undefined required class field (crossing rejection)
 *   6  a wrong-kind required class field (crossing rejection)
 *   7  a duplicate export name in the registration (protocol failure)
 *   8  an `is_async` marker that disagrees with the kind (protocol failure)
 *   9  a class record outside the module's declared class array (protocol
 *      failure)
 *  10  a required class field the host never supplied (crossing rejection)
 *  11  a `void` export returning a number (return-boundary rejection)
 *  12  `echo` returns the absence marker at its declared unknown position
 *      (return-boundary rejection: the marker is never data)
 *  13  `makeNullish` carries the absence marker in its declared `?!string`
 *      field (class-field rejection: the marker is never data)
 *  14  the registration record without its own module specifier (protocol
 *      failure, never a class-metadata arm)
 *  15  the registration record whose declared export array pointer is NULL
 *      while its count is not (protocol failure)
 *  16  the registration record whose own specifier disagrees with its registry
 *      key (protocol failure, never a class identity mismatch)
 */
#include "host/probe.h"

#include <string.h>

#ifndef DEAL_HOST_PROBE_CASE
#define DEAL_HOST_PROBE_CASE 0
#endif

static deal_host_result ok_result(deal_value value) {
  deal_host_result result;
  result.failed = 0;
  result.value = value;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = NULL;
  result.failure.message = NULL;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0;
  return result;
}

static deal_host_result host_failure(const char *code, const char *message) {
  deal_host_result result;
  result.failed = 1;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = code;
  result.failure.message = message;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0;
  result.value.slot = 0;
  result.value.generation = 0;
  return result;
}

#if DEAL_HOST_PROBE_CASE != 1
static deal_host_result sum_impl(const deal_value *args, size_t argc) {
  double left = 0.0;
  double right = 0.0;
  if (argc != 2 || deal_as_number(args[0], &left) != DEAL_OK
      || deal_as_number(args[1], &right) != DEAL_OK) {
    return host_failure("E_HOST", "sum arguments");
  }
  deal_value total;
  if (deal_number(left + right, &total) != DEAL_OK) {
    return host_failure("E_HOST", "sum construction");
  }
  return ok_result(total);
}
#endif

/* The declared scalar `boolean` result position (H2 verification 3): the host
 * reads the argument's own truth and answers a boolean carrier, so the emitted
 * host call must project an `i1` result without a second narrowing. */
static deal_host_result echo_boolean_impl(const deal_value *args, size_t argc) {
  int truth = 0;
  deal_value value;
  if (argc != 1 || deal_as_boolean(args[0], &truth) != DEAL_OK) {
    return host_failure("E_HOST", "echoBoolean argument");
  }
  if (deal_boolean(truth, &value) != DEAL_OK) {
    return host_failure("E_HOST", "echoBoolean construction");
  }
  return ok_result(value);
}

/* The declared result-only `void` position (H2/R4): a successful crossing
 * carries `deal_nothing()` and the runtime normalizes it to undefined, so the
 * DEAL side discards the call as a statement. Case 11 answers a number at that
 * position instead, which the return boundary must reject. */
static deal_host_result noop_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value value;
#if DEAL_HOST_PROBE_CASE == 11
  if (deal_number(1.0, &value) != DEAL_OK) {
    return host_failure("E_HOST", "void result construction");
  }
#else
  if (deal_nothing(&value) != DEAL_OK) {
    return host_failure("E_HOST", "void result construction");
  }
#endif
  return ok_result(value);
}

/* One empty typed array with the declared element descriptor (R4): the empty
 * array is admitted, which is what makes the descriptor-mismatch case below
 * discriminating: emptiness alone is not the defect. */
static deal_host_result make_empty_config_array_impl(const deal_value *args,
                                                     size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  if (deal_array_new_typed(0, DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, &array)
          != DEAL_OK) {
    return host_failure("E_HOST", "empty array construction");
  }
  return ok_result(array);
}

/* One empty typed array whose persistent element descriptor is another
 * declared class (R4/R11): an empty array keeps its element descriptor, so the
 * declared `Config[]` position rejects it even though it stores no element. */
static deal_host_result make_empty_other_array_impl(const deal_value *args,
                                                    size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  if (deal_array_new_typed(0, DEAL_HOST_CLASS_ATOM_PROBE_OTHER, &array)
          != DEAL_OK) {
    return host_failure("E_HOST", "empty array construction");
  }
  return ok_result(array);
}

/* One host-created function value (H4): `deal_function_new` carries the host
 * entry and its declared signature atom, so DEAL's checked extraction and a
 * host application reach the same code entry. */
static deal_host_result host_function(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value value;
  if (deal_number(41.0, &value) != DEAL_OK) {
    return host_failure("E_HOST", "function result construction");
  }
  return ok_result(value);
}

static deal_host_result make_function_impl(const deal_value *args,
                                           size_t argc) {
  (void)args;
  (void)argc;
  deal_value function;
  if (deal_function_new(host_function, "()->number", &function) != DEAL_OK) {
    return host_failure("E_HOST", "function value construction");
  }
  return ok_result(function);
}

/* The host applies a DEAL-side function value through the one boxed apply
 * convention (H4): a failed application is the runtime's relay record and is
 * returned unchanged. */
static deal_host_result apply_function_impl(const deal_value *args,
                                            size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the applied function argument");
  }
  deal_host_result record;
  if (deal_apply(args[0], NULL, 0u, &record) != DEAL_OK) {
    return host_failure("E_HOST", "the applied function status");
  }
  return record;
}

static deal_host_result make_config_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value object;
  if (deal_host_probe_Config_new(&object) != DEAL_OK) {
    return host_failure("E_HOST", "config construction");
  }
#if DEAL_HOST_PROBE_CASE == 10
  /* The host supplies no field at all: a required field is absent and the
   * crossing rejects it with its no-value/host cell arm (H2/R2). */
#else
  deal_value value;
#if DEAL_HOST_PROBE_CASE == 5
  /* A required field the declaration does not admit: `undefined` is present on
   * the host carrier but must fail the declared `number` slot (H2/R11). */
  if (deal_undefined(&value) != DEAL_OK
      || deal_host_probe_Config_value_set(object, value) != DEAL_OK) {
    return host_failure("E_HOST", "config field store");
  }
#elif DEAL_HOST_PROBE_CASE == 6
  /* A wrong-kind required field: a string in the declared `number` slot. */
  if (deal_string_new("wrong", 5, &value) != DEAL_OK
      || deal_host_probe_Config_value_set(object, value) != DEAL_OK) {
    return host_failure("E_HOST", "config field store");
  }
#else
  if (deal_number(42.0, &value) != DEAL_OK
      || deal_host_probe_Config_value_set(object, value) != DEAL_OK) {
    return host_failure("E_HOST", "config construction");
  }
#endif
#endif
  return ok_result(object);
}

/* One host-built builtin `Error` (H2 verification 4): the declared `code` and
 * `message` fields are set on a class carrier whose atom is `Error`, and the
 * crossing materializes it into the managed Error instance. */
static deal_host_result make_error_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value atom;
  deal_value object;
  deal_value code;
  deal_value message;
  if (deal_class_atom("Error", &atom) != DEAL_OK
      || deal_class_new(atom, &object) != DEAL_OK
      || deal_string_new("E_HOST_BUILT", 12, &code) != DEAL_OK
      || deal_string_new("the host built this error", 25, &message) != DEAL_OK
      || deal_field_set(object, "code", code) != DEAL_OK
      || deal_field_set(object, "message", message) != DEAL_OK) {
    return host_failure("E_HOST", "error construction");
  }
  return ok_result(object);
}

/* One typed array of the declared `Config` element type whose only slot names a
 * host-built instance of another declared class (R11, H2 verification 3): the
 * element position's own descriptor check must reject it, so the wrong layout
 * never becomes DEAL data. */
static deal_host_result make_wrong_element_impl(const deal_value *args,
                                                size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  deal_value element;
  deal_value value;
  deal_value label;
  if (deal_array_new_typed(1, DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, &array)
          != DEAL_OK
      || deal_host_probe_Other_new(&element) != DEAL_OK
      || deal_number(9.0, &value) != DEAL_OK
      || deal_string_new("other", 5, &label) != DEAL_OK
      || deal_host_probe_Other_value_set(element, value) != DEAL_OK
      || deal_host_probe_Other_label_set(element, label) != DEAL_OK
      || deal_array_set(array, 0, element) != DEAL_OK) {
    return host_failure("E_HOST", "wrong-element array construction");
  }
  return ok_result(array);
}

/* One typed array whose two slots name one host-built instance, so the DEAL
 * side observes one materialized value (R11 alias identity). */
static deal_host_result make_array_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  deal_value element;
  deal_value value;
  if (deal_array_new_typed(2, DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, &array)
          != DEAL_OK
      || deal_host_probe_Config_new(&element) != DEAL_OK
      || deal_number(42.0, &value) != DEAL_OK
      || deal_host_probe_Config_value_set(element, value) != DEAL_OK
      || deal_array_set(array, 0, element) != DEAL_OK
      || deal_array_set(array, 1, element) != DEAL_OK) {
    return host_failure("E_HOST", "array construction");
  }
  return ok_result(array);
}

/* The retained-reference surface of H3/R11: a retained class instance names its
 * own nominal identity, `deal_handle_value` rejects another class identity with
 * the closed kind status and roundtrips the instance under `unknown`, and a
 * released handle never resolves again. */
static deal_host_result handle_probe_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value object;
  deal_value value;
  deal_handle handle = 0;
  if (deal_host_probe_Config_new(&object) != DEAL_OK
      || deal_number(9.0, &value) != DEAL_OK
      || deal_host_probe_Config_value_set(object, value) != DEAL_OK
      || deal_retain(object, &handle) != DEAL_OK || handle == 0) {
    return host_failure("E_HOST", "the retained class instance");
  }
  deal_value restored;
  if (deal_handle_value(handle, DEAL_HOST_CLASS_ATOM_PROBE_OTHER, &restored)
          != DEAL_STATUS_KIND) {
    return host_failure("E_HOST",
                        "a foreign class identity is not a kind status");
  }
  if (deal_handle_value(handle, "unknown", &restored) != DEAL_OK) {
    return host_failure("E_HOST", "the class does not roundtrip as unknown");
  }
  const char *identity = NULL;
  if (deal_class_identity(restored, &identity) != DEAL_OK || identity == NULL
      || strcmp(identity, DEAL_HOST_CLASS_ATOM_PROBE_CONFIG) != 0) {
    return host_failure("E_HOST", "the retained nominal identity");
  }
  /* The published nullish grammar: the canonical both-member prefix (`?!T`) is
   * accepted wherever a descriptor text arrives, and every non-canonical shape
   * is the closed `bad-descriptor` status, never an infrastructure fault
   * (spec `RuntimeTypeDescriptor`, R11). */
  deal_value carrier;
  if (deal_array_new_typed(0, "?!number", &carrier) != DEAL_OK) {
    return host_failure("E_HOST", "the canonical nullish element descriptor");
  }
  static const char *const noncanonical[] = {
      "??number", "!?number", "!!number", "?unknown", "?null",
      "!undefined", "?null|undefined", "?void"};
  for (size_t index = 0;
       index < sizeof noncanonical / sizeof noncanonical[0]; index += 1u) {
    if (deal_array_new_typed(0, noncanonical[index], &carrier)
            != DEAL_STATUS_BAD_DESCRIPTOR) {
      return host_failure("E_HOST", "a non-canonical nullish descriptor");
    }
  }
  if (deal_handle_value(handle, "?!@$external/host/probe/Config", &restored)
          != DEAL_OK) {
    return host_failure("E_HOST", "the canonical nullish handle descriptor");
  }
  deal_value read;
  double number = 0.0;
  if (deal_field_get(restored, "value", &read) != DEAL_OK
      || deal_as_number(read, &number) != DEAL_OK || number != 9.0) {
    return host_failure("E_HOST", "the retained field value");
  }
  if (deal_release(handle) != DEAL_OK) {
    return host_failure("E_HOST", "the handle release");
  }
  if (deal_handle_value(handle, "unknown", &restored)
          != DEAL_STATUS_STALE_HANDLE) {
    return host_failure("E_HOST", "a released handle is not stale");
  }
  deal_value one;
  if (deal_number(1.0, &one) != DEAL_OK) {
    return host_failure("E_HOST", "observation construction");
  }
  return ok_result(one);
}

/* One host-applied DEAL function value (H4): `deal_apply` runs the crossing
 * with the free function's own boxed apply thunk, so a DEAL failure inside it
 * is H2's relay record — failed, the DEAL_ERROR channel, one live token and the
 * class carrier of that very Error, whose current fields the host observes. */
static deal_status applied_failure(const deal_value function,
                                   deal_host_result *record) {
  if (deal_apply(function, NULL, 0u, record) != DEAL_OK) {
    return DEAL_STATUS_PROTOCOL;
  }
  if (record->failed == 0
      || record->failure.kind != DEAL_FAILURE_DEAL_ERROR
      || record->failure.error == 0
      || record->failure.code != NULL || record->failure.message != NULL) {
    return DEAL_STATUS_KIND;
  }
  deal_value_kind kind = DEAL_KIND_NULL;
  if (deal_kind_of(record->value, &kind) != DEAL_OK || kind != DEAL_KIND_CLASS) {
    return DEAL_STATUS_KIND;
  }
  const char *identity = NULL;
  if (deal_class_identity(record->value, &identity) != DEAL_OK
      || identity == NULL || strcmp(identity, "Error") != 0) {
    return DEAL_STATUS_KIND;
  }
  deal_value code;
  const void *bytes = NULL;
  size_t length = 0;
  if (deal_field_get(record->value, "code", &code) != DEAL_OK
      || deal_as_string(code, &bytes, &length) != DEAL_OK
      || length != strlen("E_APPLY_MUTATED")
      || memcmp(bytes, "E_APPLY_MUTATED", length) != 0) {
    return DEAL_STATUS_KIND;
  }
  return DEAL_OK;
}

static deal_host_result apply_relay_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the applied function argument");
  }
  deal_host_result record;
  if (applied_failure(args[0], &record) != DEAL_OK) {
    return host_failure("E_HOST", "the applied function failure record");
  }
  /* Relayed unchanged: the runtime consumes the token and re-admits the
   * identical Error (H2/H4). */
  return record;
}

static deal_host_result apply_discard_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the applied function argument");
  }
  deal_host_result record;
  if (applied_failure(args[0], &record) != DEAL_OK) {
    return host_failure("E_HOST", "the applied function failure record");
  }
  if (deal_failure_discard(record.failure.error) != DEAL_OK) {
    return host_failure("E_HOST", "the applied function discard");
  }
  deal_value value;
  if (deal_number(5.0, &value) != DEAL_OK) {
    return host_failure("E_HOST", "the applied function observation");
  }
  return ok_result(value);
}

static deal_host_result host_text_failure_impl(const deal_value *args,
                                               size_t argc) {
  (void)args;
  (void)argc;
  return host_failure("E_HOST_TEXT", "the host refused the call");
}

/* One host-built instance whose declared field is itself a class (H2
 * verification 4): the nested instance crosses at the field's own declared
 * position, so the nested materialization path is the executed one. */
static deal_host_result make_holder_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value holder;
  deal_value inner;
  deal_value value;
  if (deal_host_probe_Holder_new(&holder) != DEAL_OK
      || deal_host_probe_Config_new(&inner) != DEAL_OK
      || deal_number(42.0, &value) != DEAL_OK
      || deal_host_probe_Config_value_set(inner, value) != DEAL_OK
      || deal_host_probe_Holder_inner_set(holder, inner) != DEAL_OK) {
    return host_failure("E_HOST", "holder construction");
  }
  return ok_result(holder);
}

/* One instance of the class that declares no field (H2 verification 4): the
 * crossing itself must identify the instance by its nominal tag alone, so the
 * declared class position admits it without a field to read. */
static deal_host_result make_empty_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value object;
  if (deal_host_probe_Empty_new(&object) != DEAL_OK) {
    return host_failure("E_HOST", "empty class construction");
  }
  return ok_result(object);
}

/* The host view of one zero-declared-field instance (H2 verification 4, R11):
 * the managed instance identifies itself by its nominal tag alone, so the value
 * surface answers its own identity and its own absent declared fields instead
 * of falling into another class's or the builtin Error record's layout. */
static deal_host_result probe_empty_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the probed instance argument");
  }
  const char *identity = NULL;
  if (deal_class_identity(args[0], &identity) != DEAL_OK || identity == NULL
      || strcmp(identity, DEAL_HOST_CLASS_ATOM_PROBE_EMPTY) != 0) {
    return host_failure("E_HOST", "the zero-declared-field class identity");
  }
  int present = 1;
  if (deal_field_has(args[0], "code", &present)
          != DEAL_STATUS_UNKNOWN_FIELD) {
    return host_failure("E_HOST", "the zero-declared-field class presence");
  }
  deal_value read;
  if (deal_field_get(args[0], "message", &read)
          != DEAL_STATUS_UNKNOWN_FIELD) {
    return host_failure("E_HOST", "the zero-declared-field class field");
  }
  deal_value one;
  if (deal_number(1.0, &one) != DEAL_OK) {
    return host_failure("E_HOST", "observation construction");
  }
  if (deal_field_set(args[0], "code", one) != DEAL_STATUS_UNKNOWN_FIELD) {
    return host_failure("E_HOST", "the zero-declared-field class store");
  }
  return ok_result(one);
}

/* The host view of one DEAL-held instance (R11 `HostValueSurface`): a store
 * through the view is checked against the declared slot and commits nothing on
 * rejection, and an admitted store commits through the insertion barrier so the
 * DEAL side observes it. */
static deal_host_result probe_config_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the probed instance argument");
  }
  deal_value wrong;
  deal_value read;
  deal_value accepted;
  deal_value one;
  double number = 0.0;
  if (deal_string_new("wrong", 5, &wrong) != DEAL_OK) {
    return host_failure("E_HOST", "the wrong-kind store construction");
  }
  if (deal_field_set(args[0], "value", wrong) != DEAL_STATUS_KIND) {
    return host_failure("E_HOST", "the wrong-kind view store");
  }
  if (deal_field_get(args[0], "value", &read) != DEAL_OK
      || deal_as_number(read, &number) != DEAL_OK || number != 42.0) {
    return host_failure("E_HOST", "the rejected view store changed the value");
  }
  deal_value absent;
  if (deal_absent(&absent) != DEAL_OK
      || deal_field_set(args[0], "value", absent) != DEAL_STATUS_KIND) {
    /* The absence marker is presence metadata, never a datum (R2/R11). */
    return host_failure("E_HOST", "the absent view store");
  }
  if (deal_number(43.0, &accepted) != DEAL_OK
      || deal_field_set(args[0], "value", accepted) != DEAL_OK) {
    return host_failure("E_HOST", "the admitted view store");
  }
  if (deal_field_get(args[0], "value", &read) != DEAL_OK
      || deal_as_number(read, &number) != DEAL_OK || number != 43.0
      || deal_number(1.0, &one) != DEAL_OK) {
    return host_failure("E_HOST", "the admitted view store read");
  }
  return ok_result(one);
}

/* The pass-through of one host-held value: the argument carrier is returned
 * unchanged, so a DEAL-origin view keeps its identity across the crossing
 * (H2/R11). */
static deal_host_result echo_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the echo argument");
  }
#if DEAL_HOST_PROBE_CASE == 12
  /* The absent marker is presence metadata, never a datum: no declared data
   * position admits it, `unknown` included (R2/R11), so the return crossing
   * renders the declared position's own arm instead of admitting an
   * `undefined`. */
  deal_value absent;
  (void)args;
  if (deal_absent(&absent) != DEAL_OK) {
    return host_failure("E_HOST", "the absent carrier");
  }
  return ok_result(absent);
#else
  return ok_result(args[0]);
#endif
}

/* One malformed string carrier (H2's negative presentation, R2/R4): an unpaired
 * UTF-16 surrogate encoding, which no declared `string` position admits. The
 * value surface still carries the bytes so the declared boundary is the
 * rejecting authority. */
static deal_host_result malformed_text_impl(const deal_value *args,
                                            size_t argc) {
  static const char surrogate[3] = {(char)0xED, (char)0xA0, (char)0x80};
  (void)args;
  (void)argc;
  deal_value value;
  deal_value_kind kind = DEAL_KIND_NULL;
  if (deal_string_new(surrogate, sizeof surrogate, &value) != DEAL_OK
      || deal_kind_of(value, &kind) != DEAL_OK
      || kind != DEAL_KIND_INVALID_UNICODE) {
    return host_failure("E_HOST", "malformed text construction");
  }
  return ok_result(value);
}

/* One host-built instance whose declared `note` field is the canonical
 * both-member nullish type `string | null | undefined` (`?!string`): the unit
 * registers the declaration's own canonical field text, so the load accepts it
 * (spec `RuntimeTypeDescriptor`, H2) and the crossing admits the present null. */
static deal_host_result make_nullish_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value object;
  deal_value value;
  deal_value note;
#if DEAL_HOST_PROBE_CASE == 13
  /* The absence marker in the declared `?!string` slot: presence metadata is
   * never a datum even where the descriptor admits `undefined`, so the field's
   * own slot rejects it (R2/R11). */
  if (deal_absent(&note) != DEAL_OK) {
    return host_failure("E_HOST", "absent nullish field construction");
  }
#else
  if (deal_null(&note) != DEAL_OK) {
    return host_failure("E_HOST", "nullish field construction");
  }
#endif
  if (deal_host_probe_Nullish_new(&object) != DEAL_OK
      || deal_number(7.0, &value) != DEAL_OK
      || deal_host_probe_Nullish_value_set(object, value) != DEAL_OK
      || deal_host_probe_Nullish_note_set(object, note) != DEAL_OK) {
    return host_failure("E_HOST", "nullish class construction");
  }
  return ok_result(object);
}

/* The canonical both-member nullish result position (`?!number`): the declared
 * descriptor text is the one the runtime grammar must accept, and the crossing
 * admits the present null. */
static deal_host_result pick_nullish_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value value;
  if (deal_null(&value) != DEAL_OK) {
    return host_failure("E_HOST", "nullish result construction");
  }
  return ok_result(value);
}

/* A typed array whose persistent element descriptor is the canonical
 * both-member nullish text (`[?!number]`): `deal_array_new_typed` accepts it and
 * the declared element position admits the present null beside a number. */
static deal_host_result nullish_list_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  deal_value none;
  deal_value three;
  if (deal_array_new_typed(2, "?!number", &array) != DEAL_OK
      || deal_null(&none) != DEAL_OK
      || deal_number(3.0, &three) != DEAL_OK
      || deal_array_set(array, 0, none) != DEAL_OK
      || deal_array_set(array, 1, three) != DEAL_OK) {
    return host_failure("E_HOST", "nullish array construction");
  }
  return ok_result(array);
}

/* The host view of one DEAL-held instance whose declared field is the canonical
 * both-member nullish type (R11): the absence marker is never a datum even in a
 * slot whose descriptor admits `undefined`, so the store answers the closed kind
 * status and the present null stays exactly as it was. */
static deal_host_result probe_nullish_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the nullish probe argument");
  }
  deal_value absent;
  if (deal_absent(&absent) != DEAL_OK
      || deal_field_set(args[0], "note", absent) != DEAL_STATUS_KIND) {
    return host_failure("E_HOST", "the absent view store at a nullish slot");
  }
  deal_value note;
  deal_value_kind kind = DEAL_KIND_UNDEFINED;
  if (deal_field_get(args[0], "note", &note) != DEAL_OK
      || deal_kind_of(note, &kind) != DEAL_OK || kind != DEAL_KIND_NULL) {
    return host_failure("E_HOST", "the rejected absent store changed the"
                        " field");
  }
  deal_value one;
  if (deal_number(1.0, &one) != DEAL_OK) {
    return host_failure("E_HOST", "observation construction");
  }
  return ok_result(one);
}

/* A host-built instance whose required `number` field carries the absence
 * marker: `missing` is presence metadata, never a datum, so the declared field
 * rejects it at the crossing instead of admitting an `undefined` (R2/R11). */
static deal_host_result absent_field_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value object;
  deal_value absent;
  if (deal_host_probe_Config_new(&object) != DEAL_OK
      || deal_absent(&absent) != DEAL_OK
      || deal_host_probe_Config_value_set(object, absent) != DEAL_OK) {
    return host_failure("E_HOST", "absent field construction");
  }
  return ok_result(object);
}

/* One sparse typed array (H2 verification 3/4): a hole below the only stored
 * element is not admitted data, so the declared element position rejects it. */
static deal_host_result make_sparse_array_impl(const deal_value *args,
                                               size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  deal_value value;
  if (deal_array_new_typed(2, "number", &array) != DEAL_OK
      || deal_number(2.0, &value) != DEAL_OK
      || deal_array_set(array, 1, value) != DEAL_OK) {
    return host_failure("E_HOST", "sparse array construction");
  }
  return ok_result(array);
}

/* One host-built record graph (H2 verification 4): a class member, a function
 * member and a string member cross at the declared `record` position, and a
 * malformed key is the closed host-boundary signal, never a stored member. */
static deal_host_result make_record_impl(const deal_value *args, size_t argc) {
  static const char malformed_key[3] = {(char)0xED, (char)0xA0, (char)0x80};
  (void)args;
  (void)argc;
  deal_value table;
  deal_value nested;
  deal_value value;
  deal_value function;
  deal_value label;
  if (deal_table_new(&table) != DEAL_OK
      || deal_host_probe_Config_new(&nested) != DEAL_OK
      || deal_number(42.0, &value) != DEAL_OK
      || deal_host_probe_Config_value_set(nested, value) != DEAL_OK
      || deal_table_set(table, "cfg", 3, nested) != DEAL_OK
      || deal_function_new(host_function, "()->number", &function) != DEAL_OK
      || deal_table_set(table, "fn", 2, function) != DEAL_OK
      || deal_string_new("record", 6, &label) != DEAL_OK
      || deal_table_set(table, "label", 5, label) != DEAL_OK) {
    return host_failure("E_HOST", "record construction");
  }
  int present = 1;
  if (deal_table_set(table, malformed_key, sizeof malformed_key, value)
          != DEAL_STATUS_KIND
      || deal_table_has(table, malformed_key, sizeof malformed_key, &present)
          != DEAL_STATUS_KIND) {
    return host_failure("E_HOST", "the malformed record key");
  }
  return ok_result(table);
}

/* One self-referential host-built graph (H2 verification 4, R11): the graph's
 * own slot names the graph, so the crossing must preserve the cycle by
 * materializing each carrier exactly once. */
static deal_host_result make_cycle_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  deal_value tail;
  if (deal_array_new_typed(2, "unknown", &array) != DEAL_OK
      || deal_string_new("tail", 4, &tail) != DEAL_OK
      || deal_array_set(array, 0, array) != DEAL_OK
      || deal_array_set(array, 1, tail) != DEAL_OK) {
    return host_failure("E_HOST", "cycle construction");
  }
  return ok_result(array);
}

/* The host applies one DEAL function value with a graph it built itself and
 * then re-reads its own carrier (H2/R11): the crossing materializes the graph
 * into DEAL data without mutating the host object, so the same slots still name
 * the same host-built instance and its declared field value is unchanged. */
static deal_host_result apply_graph_impl(const deal_value *args, size_t argc) {
  if (argc != 1u) {
    return host_failure("E_HOST", "the applied graph argument");
  }
  deal_value element;
  deal_value value;
  deal_value array;
  if (deal_host_probe_Config_new(&element) != DEAL_OK
      || deal_number(42.0, &value) != DEAL_OK
      || deal_host_probe_Config_value_set(element, value) != DEAL_OK
      || deal_array_new_typed(2, DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, &array)
          != DEAL_OK
      || deal_array_set(array, 0, element) != DEAL_OK
      || deal_array_set(array, 1, element) != DEAL_OK) {
    return host_failure("E_HOST", "the applied graph construction");
  }
  deal_host_result record;
  if (deal_apply(args[0], &array, 1u, &record) != DEAL_OK) {
    return host_failure("E_HOST", "the applied graph status");
  }
  if (record.failed != 0) {
    return record;
  }
  deal_value first;
  deal_value second;
  deal_value read;
  deal_value_kind kind = DEAL_KIND_NULL;
  double number = 0.0;
  int same = 0;
  int present = 1;
  int64_t length = 0;
  if (deal_array_length(array, &length) != DEAL_OK || length != 2
      || deal_array_get(array, 0, &first) != DEAL_OK
      || deal_array_get(array, 1, &second) != DEAL_OK
      || deal_same(first, element, &same) != DEAL_OK || same != 1
      || deal_same(second, element, &same) != DEAL_OK || same != 1
      || deal_kind_of(element, &kind) != DEAL_OK || kind != DEAL_KIND_CLASS
      || deal_field_get(element, "value", &read) != DEAL_OK
      || deal_as_number(read, &number) != DEAL_OK || number != 42.0
      || deal_field_has(element, "label", &present) != DEAL_OK
      || present != 0) {
    return host_failure("E_HOST", "the crossing mutated the host object");
  }
  return record;
}

/* The host entry of the declared `(number)->number` export below (H4): a
 * parameter position an applying host can reach with an unchecked carrier. */
static deal_host_result inner_value_impl(const deal_value *args, size_t argc) {
  double value = 0.0;
  deal_value result;
  if (argc != 1u || deal_as_number(args[0], &value) != DEAL_OK) {
    return host_failure("E_HOST", "the inner value argument");
  }
  if (deal_number(value + 1.0, &result) != DEAL_OK) {
    return host_failure("E_HOST", "the inner value construction");
  }
  return ok_result(result);
}

/* A declared function-typed result (H4): the export's own canonical signature
 * declares its result position as `()->number`, so a conforming function value
 * crosses at that position, is checked as a function and applies through the
 * host thunk. */
static deal_host_result make_inner_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value function;
  if (deal_function_new(host_function, "()->number", &function) != DEAL_OK) {
    return host_failure("E_HOST", "the function-valued result construction");
  }
  return ok_result(function);
}

/* A number at a declared `()->number` result position (H2 verification 3): the
 * declared result is the function descriptor itself, never a re-parsed text, so
 * the return boundary rejects the wrong kind. */
static deal_host_result wrong_inner_kind_impl(const deal_value *args,
                                              size_t argc) {
  (void)args;
  (void)argc;
  deal_value value;
  if (deal_number(5.0, &value) != DEAL_OK) {
    return host_failure("E_HOST", "the wrong-kind result construction");
  }
  return ok_result(value);
}

/* A function value whose own signature differs from the declared result
 * position (H4 `FUNCTION_SIGNATURE`): the carried signature atom decides. */
static deal_host_result wrong_inner_signature_impl(const deal_value *args,
                                                   size_t argc) {
  (void)args;
  (void)argc;
  deal_value function;
  if (deal_function_new(inner_value_impl, "(number)->number", &function)
          != DEAL_OK) {
    return host_failure("E_HOST", "the signature result construction");
  }
  return ok_result(function);
}

/* The untyped-array test presentation (H2): never DEAL data, including at a host
 * function value's own result boundary. */
static deal_host_result make_untyped_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value array;
  if (deal_array_new(2, &array) != DEAL_OK) {
    return host_failure("E_HOST", "the untyped carrier construction");
  }
  return ok_result(array);
}

/* The host applies a host-declared export value through `deal_apply` with a
 * wrong-kind argument (H4): the function value's own declared parameter
 * boundary runs before the host entry, so the record is the relayed DEAL
 * failure and never an unchecked call. */
static deal_host_result apply_one_impl(const deal_value *args, size_t argc) {
  deal_value wrong;
  deal_host_result record;
  if (argc != 1u) {
    return host_failure("E_HOST", "the applied parameter function argument");
  }
  if (deal_string_new("oops", 4, &wrong) != DEAL_OK) {
    return host_failure("E_HOST", "the wrong-kind argument construction");
  }
  if (deal_apply(args[0], &wrong, 1u, &record) != DEAL_OK) {
    return host_failure("E_HOST", "the applied parameter function status");
  }
  if (record.failed == 0 || record.failure.kind != DEAL_FAILURE_DEAL_ERROR) {
    return host_failure("E_HOST", "the applied parameter boundary");
  }
  return record;
}

/* The host applies a host-declared export value whose declared result is
 * `unknown` and whose entry answers the untyped presentation: the host-function
 * result boundary rejects it exactly as the synchronous crossing does. */
static deal_host_result apply_unknown_result_impl(const deal_value *args,
                                                  size_t argc) {
  deal_host_result record;
  if (argc != 1u) {
    return host_failure("E_HOST", "the applied result function argument");
  }
  if (deal_apply(args[0], NULL, 0u, &record) != DEAL_OK) {
    return host_failure("E_HOST", "the applied result function status");
  }
  if (record.failed == 0 || record.failure.kind != DEAL_FAILURE_DEAL_ERROR) {
    return host_failure("E_HOST", "the applied result boundary");
  }
  return record;
}

/* The closed statuses of an inadmissible argument presentation and of an
 * argument count the applied value's own signature does not declare (H2/H4). */
static deal_host_result untyped_argument_impl(const deal_value *args,
                                              size_t argc) {
  deal_value array;
  deal_value one;
  deal_host_result record;
  if (argc != 1u) {
    return host_failure("E_HOST", "the applied status function argument");
  }
  if (deal_array_new(2, &array) != DEAL_OK) {
    return host_failure("E_HOST", "the untyped argument construction");
  }
  if (deal_apply(args[0], &array, 1u, &record) != DEAL_STATUS_KIND) {
    return host_failure("E_HOST", "the untyped argument status");
  }
  if (deal_apply(args[0], NULL, 3u, &record) != DEAL_STATUS_PROTOCOL) {
    return host_failure("E_HOST", "the argument count status");
  }
  if (deal_number(1.0, &one) != DEAL_OK) {
    return host_failure("E_HOST", "observation construction");
  }
  return ok_result(one);
}

/* A host-built builtin `Error` that omits its required `message` field
 * (H2/R2/R11): the declared Error position renders the missing field's own host
 * cell arm instead of an infrastructure fault, exactly as a declared class's
 * missing required field does. */
static deal_host_result make_broken_error_impl(const deal_value *args,
                                               size_t argc) {
  (void)args;
  (void)argc;
  deal_value atom;
  deal_value object;
  deal_value code;
  if (deal_class_atom("Error", &atom) != DEAL_OK
      || deal_class_new(atom, &object) != DEAL_OK
      || deal_string_new("E_HOST_PARTIAL", 15, &code) != DEAL_OK
      || deal_field_set(object, "code", code) != DEAL_OK) {
    return host_failure("E_HOST", "partial error construction");
  }
  return ok_result(object);
}

/* The class-atom grammar (H2's pre-intern contract, R11): a text outside the
 * published `@modulePath/ClassName` rule is the closed bad-descriptor status —
 * never a process fault and never a silently accepted identity — while the
 * declaration's own canonical atom and the builtin `Error` atom are accepted. */
static deal_host_result class_atom_statuses_impl(const deal_value *args,
                                                 size_t argc) {
  static const char *const rejected[] = {
      "@abc", "@abc/", "@/Foo", "@$builtin/Foo", "@$builtin/a/Foo", "@"};
  (void)args;
  (void)argc;
  deal_value atom;
  deal_value one;
  for (size_t index = 0; index < sizeof rejected / sizeof rejected[0];
       index += 1u) {
    if (deal_class_atom(rejected[index], &atom)
            != DEAL_STATUS_BAD_DESCRIPTOR) {
      return host_failure("E_HOST", "a non-canonical class atom");
    }
  }
  if (deal_class_atom(DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, &atom) != DEAL_OK
      || deal_class_atom("Error", &atom) != DEAL_OK) {
    return host_failure("E_HOST", "a canonical class atom");
  }
  if (deal_number(1.0, &one) != DEAL_OK) {
    return host_failure("E_HOST", "observation construction");
  }
  return ok_result(one);
}

#if DEAL_HOST_PROBE_CASE == 2
static deal_host_result extra_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value value;
  if (deal_number(1.0, &value) != DEAL_OK) {
    return host_failure("E_HOST", "extra construction");
  }
  return ok_result(value);
}
#endif

#if DEAL_HOST_PROBE_CASE == 3
const deal_host_field deal_host_fields_probe_Config[] = {
    {"value", "number", 0},
    {"value", "number", 0},
};
#else
/* The declared layout: one required number and one optional string, so a
 * host-built instance can carry a present required field beside an absent
 * optional one (R2 own-presence). */
const deal_host_field deal_host_fields_probe_Config[] = {
    {"value", "number", 0},
    {"label", "string", 1},
};
#endif

const deal_host_field deal_host_fields_probe_Other[] = {
    {"value", "number", 0},
    {"label", "string", 0},
};

/* The declared nested class field: a class-typed slot carries the nested
 * class's canonical identity as its declared descriptor (H2/R2). */
const deal_host_field deal_host_fields_probe_Holder[] = {
    {"inner", DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, 0},
};

/* The declared both-member nullish field: the unit registers the declaration's
 * own canonical text for `string | null | undefined` (spec
 * `RuntimeTypeDescriptor`), which the load must accept. */
const deal_host_field deal_host_fields_probe_Nullish[] = {
    {"value", "number", 0},
    {"note", "?!string", 0},
};

#if DEAL_HOST_PROBE_CASE == 4
const deal_host_class deal_host_classes_probe[] = {
    {"@$external/host/other/Config", deal_host_fields_probe_Config,
     sizeof deal_host_fields_probe_Config
         / sizeof deal_host_fields_probe_Config[0]},
    {DEAL_HOST_CLASS_ATOM_PROBE_OTHER, deal_host_fields_probe_Other,
     sizeof deal_host_fields_probe_Other
         / sizeof deal_host_fields_probe_Other[0]},
    {DEAL_HOST_CLASS_ATOM_PROBE_HOLDER, deal_host_fields_probe_Holder,
     sizeof deal_host_fields_probe_Holder
         / sizeof deal_host_fields_probe_Holder[0]},
    {DEAL_HOST_CLASS_ATOM_PROBE_EMPTY, NULL, 0},
    {DEAL_HOST_CLASS_ATOM_PROBE_NULLISH, deal_host_fields_probe_Nullish,
     sizeof deal_host_fields_probe_Nullish
         / sizeof deal_host_fields_probe_Nullish[0]},
};
#else
const deal_host_class deal_host_classes_probe[] = {
    {DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, deal_host_fields_probe_Config,
     sizeof deal_host_fields_probe_Config
         / sizeof deal_host_fields_probe_Config[0]},
    {DEAL_HOST_CLASS_ATOM_PROBE_OTHER, deal_host_fields_probe_Other,
     sizeof deal_host_fields_probe_Other
         / sizeof deal_host_fields_probe_Other[0]},
    {DEAL_HOST_CLASS_ATOM_PROBE_HOLDER, deal_host_fields_probe_Holder,
     sizeof deal_host_fields_probe_Holder
         / sizeof deal_host_fields_probe_Holder[0]},
    {DEAL_HOST_CLASS_ATOM_PROBE_EMPTY, NULL, 0},
    {DEAL_HOST_CLASS_ATOM_PROBE_NULLISH, deal_host_fields_probe_Nullish,
     sizeof deal_host_fields_probe_Nullish
         / sizeof deal_host_fields_probe_Nullish[0]},
};
#endif

/* The registered class record's own class metadata (declared by the header). */
const deal_host_class deal_host_class_probe_Config = {
    DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, deal_host_fields_probe_Config,
    sizeof deal_host_fields_probe_Config
        / sizeof deal_host_fields_probe_Config[0]};

const deal_host_class deal_host_class_probe_Other = {
    DEAL_HOST_CLASS_ATOM_PROBE_OTHER, deal_host_fields_probe_Other,
    sizeof deal_host_fields_probe_Other
        / sizeof deal_host_fields_probe_Other[0]};

const deal_host_class deal_host_class_probe_Holder = {
    DEAL_HOST_CLASS_ATOM_PROBE_HOLDER, deal_host_fields_probe_Holder,
    sizeof deal_host_fields_probe_Holder
        / sizeof deal_host_fields_probe_Holder[0]};

/* A class that declares no field: its metadata array is absent (H2's
 * zero-length-array rule) and only its nominal identity is declared. */
const deal_host_class deal_host_class_probe_Empty = {
    DEAL_HOST_CLASS_ATOM_PROBE_EMPTY, NULL, 0};

const deal_host_class deal_host_class_probe_Nullish = {
    DEAL_HOST_CLASS_ATOM_PROBE_NULLISH, deal_host_fields_probe_Nullish,
    sizeof deal_host_fields_probe_Nullish
        / sizeof deal_host_fields_probe_Nullish[0]};

const deal_host_export deal_host_exports_probe[] = {
#if DEAL_HOST_PROBE_CASE == 9
  /* A class record outside the module's declared class array: one C protocol
   * shape violation, not a DEAL arm. */
  {"Config", DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, DEAL_HOST_EXPORT_CLASS,
   DEAL_HOST_PROBE_CASE == 8 ? 1 : 0, NULL, NULL,
   &deal_host_class_probe_Config},
#else
  {"Config", DEAL_HOST_CLASS_ATOM_PROBE_CONFIG, DEAL_HOST_EXPORT_CLASS,
   DEAL_HOST_PROBE_CASE == 8 ? 1 : 0, NULL, NULL,
   &deal_host_classes_probe[0]},
#endif
  {"Other", DEAL_HOST_CLASS_ATOM_PROBE_OTHER, DEAL_HOST_EXPORT_CLASS,
   0, NULL, NULL, &deal_host_classes_probe[1]},
  {"Holder", DEAL_HOST_CLASS_ATOM_PROBE_HOLDER, DEAL_HOST_EXPORT_CLASS,
   0, NULL, NULL, &deal_host_classes_probe[2]},
  {"Empty", DEAL_HOST_CLASS_ATOM_PROBE_EMPTY, DEAL_HOST_EXPORT_CLASS,
   0, NULL, NULL, &deal_host_classes_probe[3]},
  {"Nullish", DEAL_HOST_CLASS_ATOM_PROBE_NULLISH, DEAL_HOST_EXPORT_CLASS,
   0, NULL, NULL, &deal_host_classes_probe[4]},
#if DEAL_HOST_PROBE_CASE != 1
  {"sum", "(number,number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   sum_impl, NULL, NULL},
#endif
  {"echoBoolean", "(boolean)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0,
   echo_boolean_impl, NULL, NULL},
#if DEAL_HOST_PROBE_CASE == 7
  /* The same export name twice: the registration is ambiguous and the loader
   * reports the deterministic protocol failure. */
  {"sum", "(number,number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   sum_impl, NULL, NULL},
#endif
  {"makeConfig", "()->@$external/host/probe/Config", DEAL_HOST_EXPORT_FUNCTION,
   0, make_config_impl, NULL, NULL},
  {"noop", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, noop_impl, NULL, NULL},
  {"makeEmptyConfigArray", "()->[@$external/host/probe/Config]",
   DEAL_HOST_EXPORT_FUNCTION, 0, make_empty_config_array_impl, NULL, NULL},
  {"makeEmptyOtherArray", "()->[@$external/host/probe/Config]",
   DEAL_HOST_EXPORT_FUNCTION, 0, make_empty_other_array_impl, NULL, NULL},
  {"makeFunction", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0,
   make_function_impl, NULL, NULL},
  {"applyFunction", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   apply_function_impl, NULL, NULL},
  {"makeArray", "()->[@$external/host/probe/Config]",
   DEAL_HOST_EXPORT_FUNCTION, 0, make_array_impl, NULL, NULL},
  {"makeWrongElementArray", "()->[@$external/host/probe/Config]",
   DEAL_HOST_EXPORT_FUNCTION, 0, make_wrong_element_impl, NULL, NULL},
  {"handleProbe", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   handle_probe_impl, NULL, NULL},
  {"applyRelay", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   apply_relay_impl, NULL, NULL},
  {"applyDiscard", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   apply_discard_impl, NULL, NULL},
  {"makeError", "()->Error", DEAL_HOST_EXPORT_FUNCTION, 0, make_error_impl,
   NULL, NULL},
  {"makeHolder", "()->@$external/host/probe/Holder",
   DEAL_HOST_EXPORT_FUNCTION, 0, make_holder_impl, NULL, NULL},
  {"makeEmpty", "()->@$external/host/probe/Empty",
   DEAL_HOST_EXPORT_FUNCTION, 0, make_empty_impl, NULL, NULL},
  {"probeEmpty", "(@$external/host/probe/Empty)->number",
   DEAL_HOST_EXPORT_FUNCTION, 0, probe_empty_impl, NULL, NULL},
  {"probeConfig", "(@$external/host/probe/Config)->number",
   DEAL_HOST_EXPORT_FUNCTION, 0, probe_config_impl, NULL, NULL},
  {"echo", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, echo_impl,
   NULL, NULL},
  {"makeNullish", "()->@$external/host/probe/Nullish",
   DEAL_HOST_EXPORT_FUNCTION, 0, make_nullish_impl, NULL, NULL},
  {"pickNullish", "()->?!number", DEAL_HOST_EXPORT_FUNCTION, 0,
   pick_nullish_impl, NULL, NULL},
  {"nullishList", "()->[?!number]", DEAL_HOST_EXPORT_FUNCTION, 0,
   nullish_list_impl, NULL, NULL},
  {"probeNullish", "(@$external/host/probe/Nullish)->number",
   DEAL_HOST_EXPORT_FUNCTION, 0, probe_nullish_impl, NULL, NULL},
  {"absentFieldGraph", "()->@$external/host/probe/Config",
   DEAL_HOST_EXPORT_FUNCTION, 0, absent_field_impl, NULL, NULL},
  {"malformedText", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0,
   malformed_text_impl, NULL, NULL},
  {"makeSparseArray", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0,
   make_sparse_array_impl, NULL, NULL},
  {"makeRecord", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0,
   make_record_impl, NULL, NULL},
  {"makeCycle", "()->[unknown]", DEAL_HOST_EXPORT_FUNCTION, 0,
   make_cycle_impl, NULL, NULL},
  {"applyGraph", "(([@$external/host/probe/Config])->number)->number",
   DEAL_HOST_EXPORT_FUNCTION, 0, apply_graph_impl, NULL, NULL},
  /* The declared function-typed results, the host-applied parameter/result
   * boundaries, the untyped presentation, the partial Error graph and the
   * class-atom grammar (H4/R11). */
  {"innerValue", "(number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   inner_value_impl, NULL, NULL},
  {"makeInner", "()->()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   make_inner_impl, NULL, NULL},
  {"wrongInnerKind", "()->()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   wrong_inner_kind_impl, NULL, NULL},
  {"wrongInnerSignature", "()->()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   wrong_inner_signature_impl, NULL, NULL},
  {"makeUntyped", "()->unknown", DEAL_HOST_EXPORT_FUNCTION, 0,
   make_untyped_impl, NULL, NULL},
  {"applyOne", "((number)->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   apply_one_impl, NULL, NULL},
  {"applyUnknownResult", "(()->unknown)->number", DEAL_HOST_EXPORT_FUNCTION,
   0, apply_unknown_result_impl, NULL, NULL},
  {"untypedArgument", "((number)->number)->number", DEAL_HOST_EXPORT_FUNCTION,
   0, untyped_argument_impl, NULL, NULL},
  {"makeBrokenError", "()->Error", DEAL_HOST_EXPORT_FUNCTION, 0,
   make_broken_error_impl, NULL, NULL},
  {"classAtomStatuses", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   class_atom_statuses_impl, NULL, NULL},
  {"hostTextFailure", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
   host_text_failure_impl, NULL, NULL},
#if DEAL_HOST_PROBE_CASE == 2
  {"notDeclared", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, extra_impl,
   NULL, NULL},
#endif
};

#if DEAL_HOST_PROBE_CASE == 14
/* The registration record's own module specifier is absent: the record's C
 * shape is inconsistent, which is the deterministic protocol failure (H2), not
 * a declared-class-metadata arm. */
const deal_host_module deal_host_module_probe = {
    NULL,
    deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0],
    deal_host_classes_probe,
    sizeof deal_host_classes_probe / sizeof deal_host_classes_probe[0],
    NULL};
#elif DEAL_HOST_PROBE_CASE == 15
/* A nonzero declared export count with no declared export array: the same
 * record-shape protocol failure. */
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE,
    NULL,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0],
    deal_host_classes_probe,
    sizeof deal_host_classes_probe / sizeof deal_host_classes_probe[0],
    NULL};
#elif DEAL_HOST_PROBE_CASE == 16
/* The registration names another module than the registry entry that pairs it:
 * the protocol failure, never a declared class identity mismatch. */
const deal_host_module deal_host_module_probe = {
    "host/other",
    deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0],
    deal_host_classes_probe,
    sizeof deal_host_classes_probe / sizeof deal_host_classes_probe[0],
    NULL};
#else
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE,
    deal_host_exports_probe,
    sizeof deal_host_exports_probe / sizeof deal_host_exports_probe[0],
    deal_host_classes_probe,
    sizeof deal_host_classes_probe / sizeof deal_host_classes_probe[0],
    NULL};
#endif
