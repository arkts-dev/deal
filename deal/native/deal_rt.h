/*
 * deal/native/deal_rt.h — the DEAL C11 runtime ABI declaration authority.
 *
 * This header is the one published declaration surface every consumer includes:
 * the emitted project module, the generated host headers, the generated
 * registry translation unit, host implementation units and every runtime
 * translation unit. It carries, unchanged, the canonical record set of
 * `llvm-native-host-abi-and-async` H2 (field order, enum discriminants,
 * signatures, registration symbol form, registry externs and the count-zero
 * sentinel), the C4 outcome family, the `deal_origin` transport record of
 * `llvm-native-codegen-cfg-ssa` `DiagnosticOriginProjection`, and the opaque
 * runtime representations of C3.
 *
 * Every service group header (`platform.h` ... `async.h`) is reached through
 * this header: the group headers are included at the end of this file, in
 * dependency order, after every shared type exists. A consumer that includes a
 * group header directly still works, because each group header includes this
 * header first and its own guard; no group header depends on another group
 * header's declarations.
 *
 * Type opacity (`llvm-native-codegen-cfg-ssa` C2/C3, `llvm-native-host-abi-and-
 * async` H6): `deal_module` and `deal_export_entry` are incomplete types here
 * and in every runtime translation unit. Their layout exists only in the
 * emitted project module. No published header declares their members, and no
 * consumer may size, allocate, fill or read them.
 *
 * Layout of one runtime value (C3): `null` and `undefined` are the two distinct
 * immutable singletons `deal_null_singleton` and `deal_undefined_singleton`
 * (page `llvm-native-runtime-value-model` R2/C3, declared in value.h), never C
 * `NULL` and never each other; `boolean` crosses this ABI as a 0/1
 * `uint8_t`/`int`; `number` is `double`; `string`, `T[]`, the record
 * (`deal_table`, retained internal C name), class instances, function values
 * and `Error` are the opaque pointers below; the boxed carrier `deal_value`
 * exists only at the closed boundary set (R3, R11).
 */
#ifndef DEAL_NATIVE_DEAL_RT_H
#define DEAL_NATIVE_DEAL_RT_H

#include <stddef.h>
#include <stdint.h>

/* =========================================================================
 * H2 canonical declaration set (approved input, verbatim).
 * ========================================================================= */

typedef struct { uint32_t slot; uint32_t generation; } deal_value;   /* runtime-issued, crossing-scoped */
typedef uint64_t deal_handle;                                       /* host-retained reference (H3) */
typedef int64_t  deal_async_token;                                  /* runtime-allocated (H5) */
typedef uint64_t deal_error_token;   /* runtime-issued, single use, crossing-scoped */

typedef enum {
  DEAL_OK = 0,
  DEAL_STATUS_KIND, DEAL_STATUS_ABSENT, DEAL_STATUS_BOUNDS,
  DEAL_STATUS_UNKNOWN_FIELD, DEAL_STATUS_UNKNOWN_CLASS,
  DEAL_STATUS_BAD_DESCRIPTOR, DEAL_STATUS_FOREIGN_VALUE,
  DEAL_STATUS_STALE_HANDLE, DEAL_STATUS_OUT_OF_SCOPE, DEAL_STATUS_PROTOCOL
} deal_status;

typedef enum {
  DEAL_KIND_NULL, DEAL_KIND_MISSING, DEAL_KIND_BOOLEAN,
  DEAL_KIND_NUMBER, DEAL_KIND_STRING, DEAL_KIND_TABLE,
  DEAL_KIND_ARRAY, DEAL_KIND_FUNCTION, DEAL_KIND_CLASS,
  DEAL_KIND_ASYNC_OPERATION, DEAL_KIND_NOTHING, DEAL_KIND_INVALID_UNICODE,
  DEAL_KIND_UNDEFINED /* appended: preserve earlier C transport discriminants */
} deal_value_kind;   /* the closed actual-kind classification */

typedef enum { DEAL_FAILURE_HOST_TEXT = 0, DEAL_FAILURE_DEAL_ERROR = 1 } deal_failure_kind;

typedef struct {
  deal_failure_kind kind;
  const char *code;          /* DEAL_FAILURE_HOST_TEXT only; NULL otherwise */
  const char *message;       /* DEAL_FAILURE_HOST_TEXT only; NULL otherwise */
  const char *expected;      /* DEAL_FAILURE_HOST_TEXT only; NULL when absent */
  const char *actual;        /* DEAL_FAILURE_HOST_TEXT only; NULL when absent */
  deal_error_token error;    /* DEAL_FAILURE_DEAL_ERROR only; 0 otherwise */
} deal_host_failure;

typedef struct { int failed; deal_value value; deal_host_failure failure; } deal_host_result;

typedef deal_host_result (*deal_host_function)(const deal_value *args, size_t argc);
typedef deal_host_result (*deal_host_async_function)(deal_async_token token,
                                                    const deal_value *args, size_t argc);
typedef void (*deal_host_service)(void);

typedef struct { const void *data; size_t length; } deal_host_span;
typedef enum {
  DEAL_COMPLETION_NUMBER = 0, DEAL_COMPLETION_BOOLEAN,
  DEAL_COMPLETION_NULL, DEAL_COMPLETION_STRING,
  DEAL_COMPLETION_ERROR, DEAL_COMPLETION_UNDEFINED /* appended; no union payload */
} deal_host_completion_kind;
typedef struct {
  const char *code, *message;            /* required NUL-terminated text */
  const char *expected, *actual;         /* NULL = absent */
} deal_host_error_text;
typedef struct deal_host_completion {
  deal_host_completion_kind kind;
  union {
    double number; int boolean;
    deal_host_span string;
    deal_host_error_text error;
  } as;                                /* NULL/UNDEFINED arms read no union member */
} deal_host_completion;

typedef struct {
  const char *name, *descriptor;
  int optional;                        /* 0 required, 1 optional */
} deal_host_field;
typedef struct deal_host_class {
  const char *identity;                 /* canonical nominal descriptor */
  const deal_host_field *fields; size_t field_count;
} deal_host_class;
typedef enum {
  DEAL_HOST_EXPORT_FUNCTION = 0, DEAL_HOST_EXPORT_ASYNC_FUNCTION,
  DEAL_HOST_EXPORT_CLASS
} deal_host_export_kind;
typedef struct {
  const char *name, *descriptor;
  deal_host_export_kind kind;
  int is_async;                        /* 1 only for ASYNC_FUNCTION */
  deal_host_function function;
  deal_host_async_function async_function;
  const deal_host_class *class_value;
} deal_host_export;
typedef struct deal_host_module {
  const char *specifier;
  const deal_host_export *exports; size_t export_count;
  const deal_host_class *classes; size_t class_count;
  deal_host_service service;            /* NULL = no waiting-thread work */
} deal_host_module;
typedef struct {
  const char *specifier;
  const deal_host_module *module;
} deal_host_registry_entry;
extern const deal_host_registry_entry deal_host_registry[];
extern const size_t deal_host_registry_count;

/* construction (every entry returns a closed status and writes one out-parameter) */
deal_status deal_null(deal_value *out);
deal_status deal_undefined(deal_value *out);
deal_status deal_nothing(deal_value *out);
deal_status deal_absent(deal_value *out);
deal_status deal_boolean(int truth, deal_value *out);
deal_status deal_number(double value, deal_value *out);
deal_status deal_string_new(const void *bytes, size_t length, deal_value *out);
deal_status deal_array_new(int64_t length, deal_value *out);      /* malformed/untyped carrier: absent slots; never admitted as data */
deal_status deal_array_new_typed(int64_t length, const char *elementDescriptor,
                                 deal_value *out); /* persistent element descriptor; initially absent slots */
deal_status deal_table_new(deal_value *out);
deal_status deal_class_atom(const char *descriptorText, deal_value *out); /* interned class atom */
deal_status deal_class_new(deal_value atom, deal_value *out);     /* every field absent */
deal_status deal_function_new(deal_host_function fn, const char *signatureDescriptor,
                              deal_value *out);
deal_status deal_async_operation(deal_async_token token, deal_value *out);

/* inspection and scalar access */
deal_status deal_kind_of(deal_value value, deal_value_kind *out);
deal_status deal_as_boolean(deal_value value, int *out);
deal_status deal_as_number(deal_value value, double *out);
deal_status deal_as_string(deal_value value, const void **bytes, size_t *length);
deal_status deal_same(deal_value a, deal_value b, int *same);      /* the host-visible === */

/* containers */
deal_status deal_array_length(deal_value array, int64_t *out);
deal_status deal_array_get(deal_value array, int64_t index, deal_value *out);
deal_status deal_array_set(deal_value array, int64_t index, deal_value element);
deal_status deal_table_get(deal_value table, const void *key, size_t keyLength, deal_value *out);
deal_status deal_table_set(deal_value table, const void *key, size_t keyLength, deal_value value);
deal_status deal_table_has(deal_value table, const void *key, size_t keyLength, int *present);
deal_status deal_table_delete(deal_value table, const void *key, size_t keyLength);

/* classes */
deal_status deal_class_identity(deal_value value, const char **descriptorText);
deal_status deal_field_get(deal_value object, const char *field, deal_value *out);
deal_status deal_field_set(deal_value object, const char *field, deal_value value);
deal_status deal_field_has(deal_value object, const char *field, int *present);
deal_status deal_field_delete(deal_value object, const char *field);

/* function values */
deal_status deal_function_signature(deal_value value, const char **descriptorText);
deal_status deal_apply(deal_value function, const deal_value *args, size_t argc,
                       deal_host_result *out);

/* host-retained references (H3) */
deal_status deal_retain(deal_value value, deal_handle *out);
deal_status deal_handle_value(deal_handle handle, const char *descriptorText, deal_value *out);
deal_status deal_release(deal_handle handle);

/* the DEAL-origin failure channel */
deal_status deal_failure_discard(deal_error_token token);
deal_status deal_error_from_handle(deal_handle handle, deal_host_failure *out);

/* async completion intake (H5) */
deal_status deal_async_complete_value(deal_async_token token, deal_value value);
deal_status deal_async_complete_error(deal_async_token token, const deal_host_failure *failure);
deal_status deal_async_post(deal_async_token token, const deal_host_completion *completion);

/* =========================================================================
 * C3 runtime representations. Every one of these is opaque: no published
 * header exposes a member, an offset, a size or an object header. Generated
 * code holds the typed pointer and reaches the value through the group
 * entries of this ABI.
 * ========================================================================= */

typedef struct deal_string deal_string;          /* immutable UTF-8 buffer + cached facts */
typedef struct deal_array deal_array;            /* zero-based typed element storage */
typedef struct deal_table deal_table;            /* insertion-ordered string-keyed store */
typedef struct deal_object deal_object;          /* per-layout class instance */
typedef struct deal_fn deal_fn;                  /* code entry, environment, signature atom */
typedef struct deal_error deal_error;            /* the builtin nominal Error instance */
typedef struct deal_async_op deal_async_op;      /* one live async operation record */

/* C2/C3/H6 opacity: the emitted project module owns both layouts. */
typedef struct deal_module deal_module;
typedef struct deal_export_entry deal_export_entry;

/* =========================================================================
 * DiagnosticOriginProjection: the origin every runtime check, load, JSON and
 * error-materialization entry receives from generated code. `source_file` is
 * the file text as compiled (project-relative display name), `line` and
 * `column` are the op's one-based values. No later group re-derives or
 * re-bases them.
 * ========================================================================= */
typedef struct {
  const char *source_file;
  int32_t line;
  int32_t column;
} deal_origin;

/* =========================================================================
 * C4 outcome family. `error` is always the first member; `error == NULL`
 * means success and on failure the value member is never read. The
 * error-only shape carries a success with no value slot: a result-only `void`,
 * a statement, and a start/run-main/host-load result. A `null` or `undefined`
 * result is rebuilt from its own singleton, never from C `NULL`, so no result
 * position confuses `null`, `undefined` and `void`; the typed shapes carry the
 * result value with its C3 representation.
 * ========================================================================= */
typedef struct { deal_error *error; } deal_outcome;
typedef struct { deal_error *error; deal_value value; } deal_outcome_value;
typedef struct { deal_error *error; double value; } deal_outcome_number;
typedef struct { deal_error *error; uint8_t value; } deal_outcome_boolean;
typedef struct { deal_error *error; deal_string *value; } deal_outcome_string;
typedef struct { deal_error *error; void *value; } deal_outcome_reference;

/* =========================================================================
 * The service group entries, in dependency order
 * (platform <- value <- memory <- text <- container <- descriptor <- error <-
 * json <- std <- host <- async).
 * ========================================================================= */
#include "deal/native/platform.h"
#include "deal/native/value.h"
#include "deal/native/memory.h"
#include "deal/native/text.h"
#include "deal/native/container.h"
#include "deal/native/descriptor.h"
#include "deal/native/error.h"
#include "deal/native/json.h"
#include "deal/native/std.h"
#include "deal/native/host.h"
#include "deal/native/async.h"

#endif /* DEAL_NATIVE_DEAL_RT_H */
