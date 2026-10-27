/*
 * test/native/abi_probe.c — H2 record conformance of the published ABI.
 *
 * This is a strict-C11 consumer that includes only the published headers
 * (`deal/native/deal_rt.h` and the group headers reached through it). It
 * constructs every canonical record and every completion arm, asserts field
 * order, enum discriminants and sizes, and defines the count-zero registry
 * sentinel exactly as the generated registry translation unit does.
 *
 * It defines the whole program, so a successful build-and-run proves the
 * published header set is standalone and the sentinel shape links. It
 * references no runtime or module entry: the declared-entry signatures are
 * asserted by the compile-only `abi_signatures.c` consumer, and the linked
 * entry surface by the build-shape cases that call the implemented entries.
 */
#include "deal/native/deal_rt.h"
#include "deal/native/entry.h"

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

/* =========================================================================
 * The count-zero registry sentinel (H2): defined exactly once by the generated
 * scratch registry translation unit when no host unit is supplied.
 * ========================================================================= */
const deal_host_registry_entry deal_host_registry[1] = {{NULL, NULL}};
const size_t deal_host_registry_count = 0;

/* A declared host module record, as a generated host header/unit would carry. */
static const deal_host_field fields[1] = {{"value", "number", 0}};
static const deal_host_class classes[1] = {{"@probe/HostClass", fields, 1}};
static const deal_host_export exports[1] = {
    {"ping", "function(;number)", DEAL_HOST_EXPORT_FUNCTION, 0,
     (deal_host_function)0, (deal_host_async_function)0, (const deal_host_class *)0}
};
const deal_host_module deal_host_module_probe = {
    "probe/host", exports, 1, classes, 1, (deal_host_service)0
};

/* =========================================================================
 * Enum discriminants (H2): every closed member in its declared order.
 * ========================================================================= */
_Static_assert(DEAL_OK == 0, "DEAL_OK is the zero status");
_Static_assert(DEAL_STATUS_KIND == 1 && DEAL_STATUS_ABSENT == 2
    && DEAL_STATUS_BOUNDS == 3 && DEAL_STATUS_UNKNOWN_FIELD == 4
    && DEAL_STATUS_UNKNOWN_CLASS == 5 && DEAL_STATUS_BAD_DESCRIPTOR == 6
    && DEAL_STATUS_FOREIGN_VALUE == 7 && DEAL_STATUS_STALE_HANDLE == 8
    && DEAL_STATUS_OUT_OF_SCOPE == 9 && DEAL_STATUS_PROTOCOL == 10,
    "the closed deal_status set is declared in its pinned order");

_Static_assert(DEAL_KIND_NULL == 0 && DEAL_KIND_MISSING == 1
    && DEAL_KIND_BOOLEAN == 2 && DEAL_KIND_NUMBER == 3
    && DEAL_KIND_STRING == 4 && DEAL_KIND_TABLE == 5
    && DEAL_KIND_ARRAY == 6 && DEAL_KIND_FUNCTION == 7
    && DEAL_KIND_CLASS == 8 && DEAL_KIND_ASYNC_OPERATION == 9
    && DEAL_KIND_NOTHING == 10 && DEAL_KIND_INVALID_UNICODE == 11
    && DEAL_KIND_UNDEFINED == 12,
    "the closed actual-kind classification is declared in its pinned order, "
    "with undefined appended after the earlier transport discriminants");

_Static_assert(DEAL_FAILURE_HOST_TEXT == 0 && DEAL_FAILURE_DEAL_ERROR == 1,
    "the two failure classes are declared in their pinned order");
_Static_assert(DEAL_HOST_EXPORT_FUNCTION == 0
    && DEAL_HOST_EXPORT_ASYNC_FUNCTION == 1 && DEAL_HOST_EXPORT_CLASS == 2,
    "the export kinds are declared in their pinned order");
_Static_assert(DEAL_COMPLETION_NUMBER == 0 && DEAL_COMPLETION_BOOLEAN == 1
    && DEAL_COMPLETION_NULL == 2 && DEAL_COMPLETION_STRING == 3
    && DEAL_COMPLETION_ERROR == 4 && DEAL_COMPLETION_UNDEFINED == 5,
    "the completion arms are declared in their pinned order, with undefined "
    "appended and no union payload");

/* =========================================================================
 * Record shapes (H2): field order, then member sizes.
 * ========================================================================= */
_Static_assert(offsetof(deal_value, slot) == 0, "deal_value.slot is first");
_Static_assert(offsetof(deal_value, generation) > offsetof(deal_value, slot),
    "deal_value.generation follows slot");
_Static_assert(sizeof(deal_value) == 2 * sizeof(uint32_t),
    "deal_value is the 64-bit slot/generation pair");
_Static_assert(sizeof(deal_handle) == sizeof(uint64_t), "handles are 64-bit");
_Static_assert(sizeof(deal_async_token) == sizeof(int64_t),
    "async tokens are 64-bit");
_Static_assert(sizeof(deal_error_token) == sizeof(uint64_t),
    "error tokens are 64-bit");

_Static_assert(offsetof(deal_host_failure, kind) == 0,
    "deal_host_failure.kind is first");
_Static_assert(offsetof(deal_host_failure, code)
    > offsetof(deal_host_failure, kind), "deal_host_failure.code follows kind");
_Static_assert(offsetof(deal_host_failure, message)
    > offsetof(deal_host_failure, code),
    "deal_host_failure.message follows code");
_Static_assert(offsetof(deal_host_failure, expected)
    > offsetof(deal_host_failure, message),
    "deal_host_failure.expected follows message");
_Static_assert(offsetof(deal_host_failure, actual)
    > offsetof(deal_host_failure, expected),
    "deal_host_failure.actual follows expected");
_Static_assert(offsetof(deal_host_failure, error)
    > offsetof(deal_host_failure, actual),
    "deal_host_failure.error follows actual");

_Static_assert(offsetof(deal_host_result, failed) == 0,
    "deal_host_result.failed is first");
_Static_assert(offsetof(deal_host_result, value)
    > offsetof(deal_host_result, failed), "the value follows failed");
_Static_assert(offsetof(deal_host_result, failure)
    > offsetof(deal_host_result, value), "the failure follows the value");

_Static_assert(offsetof(deal_host_completion, kind) == 0,
    "deal_host_completion.kind is first");
_Static_assert(offsetof(deal_host_error_text, code) == 0,
    "deal_host_error_text.code is first");
_Static_assert(offsetof(deal_host_error_text, message)
    > offsetof(deal_host_error_text, code), "message follows code");
_Static_assert(offsetof(deal_host_error_text, expected)
    > offsetof(deal_host_error_text, message), "expected follows message");
_Static_assert(offsetof(deal_host_error_text, actual)
    > offsetof(deal_host_error_text, expected), "actual follows expected");
_Static_assert(offsetof(deal_host_span, data) == 0, "a span starts with data");
_Static_assert(offsetof(deal_host_span, length) > offsetof(deal_host_span, data),
    "a span carries its length");

_Static_assert(offsetof(deal_host_field, name) == 0, "a field starts with name");
_Static_assert(offsetof(deal_host_field, descriptor)
    > offsetof(deal_host_field, name), "descriptor follows name");
_Static_assert(offsetof(deal_host_field, optional)
    > offsetof(deal_host_field, descriptor), "optional follows descriptor");
_Static_assert(offsetof(deal_host_class, identity) == 0,
    "a class record starts with its identity");
_Static_assert(offsetof(deal_host_class, fields)
    > offsetof(deal_host_class, identity), "fields follow identity");
_Static_assert(offsetof(deal_host_class, field_count)
    > offsetof(deal_host_class, fields), "field_count follows fields");
_Static_assert(offsetof(deal_host_export, name) == 0,
    "an export starts with its name");
_Static_assert(offsetof(deal_host_export, descriptor)
    > offsetof(deal_host_export, name), "descriptor follows name");
_Static_assert(offsetof(deal_host_export, kind)
    > offsetof(deal_host_export, descriptor), "kind follows descriptor");
_Static_assert(offsetof(deal_host_export, is_async)
    > offsetof(deal_host_export, kind), "is_async follows kind");
_Static_assert(offsetof(deal_host_export, function)
    > offsetof(deal_host_export, is_async), "function follows is_async");
_Static_assert(offsetof(deal_host_export, async_function)
    > offsetof(deal_host_export, function), "async_function follows function");
_Static_assert(offsetof(deal_host_export, class_value)
    > offsetof(deal_host_export, async_function),
    "class_value follows async_function");
_Static_assert(offsetof(deal_host_module, specifier) == 0,
    "a host module starts with its specifier");
_Static_assert(offsetof(deal_host_module, exports)
    > offsetof(deal_host_module, specifier), "exports follow specifier");
_Static_assert(offsetof(deal_host_module, export_count)
    > offsetof(deal_host_module, exports), "export_count follows exports");
_Static_assert(offsetof(deal_host_module, classes)
    > offsetof(deal_host_module, export_count), "classes follow export_count");
_Static_assert(offsetof(deal_host_module, class_count)
    > offsetof(deal_host_module, classes), "class_count follows classes");
_Static_assert(offsetof(deal_host_module, service)
    > offsetof(deal_host_module, class_count), "service follows class_count");
_Static_assert(offsetof(deal_host_registry_entry, specifier) == 0,
    "a registry entry starts with its specifier");
_Static_assert(offsetof(deal_host_registry_entry, module)
    > offsetof(deal_host_registry_entry, specifier),
    "the module pointer follows the specifier");

_Static_assert(offsetof(deal_origin, source_file) == 0,
    "deal_origin.source_file is first");
_Static_assert(offsetof(deal_origin, line) > offsetof(deal_origin, source_file),
    "line follows source_file");
_Static_assert(offsetof(deal_origin, column) > offsetof(deal_origin, line),
    "column follows line");
_Static_assert(sizeof(((deal_origin *)0)->line) == sizeof(int32_t),
    "the origin line is a 32-bit one-based value");
_Static_assert(sizeof(((deal_origin *)0)->column) == sizeof(int32_t),
    "the origin column is a 32-bit one-based value");

_Static_assert(offsetof(deal_outcome, error) == 0,
    "the outcome carries the error first");
_Static_assert(offsetof(deal_outcome_value, error) == 0,
    "the boxed outcome carries the error first");
_Static_assert(offsetof(deal_outcome_value, value)
    > offsetof(deal_outcome_value, error), "the boxed value follows the error");
_Static_assert(offsetof(deal_outcome_number, error) == 0,
    "the number outcome carries the error first");
_Static_assert(offsetof(deal_outcome_boolean, error) == 0,
    "the boolean outcome carries the error first");
_Static_assert(offsetof(deal_outcome_string, error) == 0,
    "the string outcome carries the error first");
_Static_assert(offsetof(deal_outcome_reference, error) == 0,
    "the reference outcome carries the error first");

_Static_assert(offsetof(deal_arm_record, id) == 0, "an arm starts with its id");
_Static_assert(offsetof(deal_arm_record, code) > offsetof(deal_arm_record, id),
    "code follows id");
_Static_assert(offsetof(deal_arm_record, template_text)
    > offsetof(deal_arm_record, code), "template_text follows code");
_Static_assert(offsetof(deal_arm_record, parameter_names)
    > offsetof(deal_arm_record, template_text),
    "parameter_names follow template_text");
_Static_assert(offsetof(deal_arm_record, parameter_count)
    > offsetof(deal_arm_record, parameter_names),
    "parameter_count follows parameter_names");
_Static_assert(offsetof(deal_arm_record, render_scope)
    > offsetof(deal_arm_record, parameter_count),
    "render_scope follows parameter_count");
_Static_assert(offsetof(deal_arm_record, has_expected)
    > offsetof(deal_arm_record, render_scope),
    "has_expected follows render_scope");
_Static_assert(offsetof(deal_arm_record, has_actual)
    > offsetof(deal_arm_record, has_expected),
    "has_actual follows has_expected");
_Static_assert(offsetof(deal_arm_table, version) == 0,
    "an arm table starts with its version");
_Static_assert(offsetof(deal_arm_table, count) > offsetof(deal_arm_table, version),
    "count follows version");
_Static_assert(offsetof(deal_arm_table, records)
    > offsetof(deal_arm_table, count), "records follow count");

_Static_assert(offsetof(deal_gc_statistics_record, live_bytes) == 0,
    "the statistics record starts with live_bytes");

/* The host load surface: both records are H2-declared-type members only. */
_Static_assert(offsetof(deal_host_declared_module, specifier) == 0,
    "a declared module starts with its raw specifier");
_Static_assert(offsetof(deal_host_declared_module, module_path)
    > offsetof(deal_host_declared_module, specifier),
    "the module path follows the specifier");
_Static_assert(offsetof(deal_host_declared_module, exports)
    > offsetof(deal_host_declared_module, module_path),
    "the declared exports follow the module path");
_Static_assert(offsetof(deal_host_declared_module, export_count)
    > offsetof(deal_host_declared_module, exports),
    "the declared export count follows the exports");
_Static_assert(offsetof(deal_host_declared_module, classes)
    > offsetof(deal_host_declared_module, export_count),
    "the declared class metadata follows the export count");
_Static_assert(offsetof(deal_host_declared_module, class_count)
    > offsetof(deal_host_declared_module, classes),
    "the declared class count follows the class metadata");
_Static_assert(offsetof(deal_host_binding, specifier) == 0,
    "a binding starts with its raw specifier");
_Static_assert(offsetof(deal_host_binding, module)
    > offsetof(deal_host_binding, specifier),
    "the validated registration follows the specifier");
_Static_assert(offsetof(deal_host_binding, exports)
    > offsetof(deal_host_binding, module),
    "the validated exports follow the registration");
_Static_assert(offsetof(deal_host_binding, export_count)
    > offsetof(deal_host_binding, exports),
    "the validated export count follows the exports");

/* =========================================================================
 * Runtime construction of every record and completion arm.
 * ========================================================================= */
int main(void) {
    deal_host_failure host_text;
    host_text.kind = DEAL_FAILURE_HOST_TEXT;
    host_text.code = "E_HOST";
    host_text.message = "host authored";
    host_text.expected = NULL;
    host_text.actual = NULL;
    host_text.error = 0U;

    deal_host_failure relayed;
    relayed.kind = DEAL_FAILURE_DEAL_ERROR;
    relayed.code = NULL;
    relayed.message = NULL;
    relayed.expected = NULL;
    relayed.actual = NULL;
    relayed.error = 42U;

    deal_host_result result;
    result.failed = 1;
    result.value.slot = 3U;
    result.value.generation = 7U;
    result.failure = host_text;

    deal_host_completion completion;
    completion.kind = DEAL_COMPLETION_NUMBER;
    completion.as.number = -0.0;
    completion.kind = DEAL_COMPLETION_BOOLEAN;
    completion.as.boolean = 1;
    completion.kind = DEAL_COMPLETION_NULL;
    completion.kind = DEAL_COMPLETION_STRING;
    {
        static const char bytes[] = "copied";
        completion.as.string.data = bytes;
        completion.as.string.length = sizeof bytes - 1;
    }
    completion.kind = DEAL_COMPLETION_ERROR;
    completion.as.error.code = "E_HOST";
    completion.as.error.message = "host authored";
    completion.as.error.expected = "string";
    completion.as.error.actual = "number";
    if (completion.kind != DEAL_COMPLETION_ERROR
            || completion.as.error.code == NULL
            || completion.as.error.actual == NULL) {
        printf("abi completion error arm failed\n");
        return 1;
    }
    /* The appended undefined arm is its own discriminant and reads no union
     * member, so a stale payload in the union is never observed. */
    completion.as.number = -0.0;
    completion.kind = DEAL_COMPLETION_UNDEFINED;

    deal_host_span span;
    span.data = NULL;
    span.length = 0;

    deal_host_declared_module declared;
    declared.specifier = "probe/host";
    declared.module_path = "probe/host";
    declared.exports = &exports[0];
    declared.export_count = 1;
    declared.classes = &classes[0];
    declared.class_count = 1;

    deal_host_binding binding;
    binding.specifier = "probe/host";
    binding.module = &deal_host_module_probe;
    binding.exports = NULL;
    binding.export_count = 0;

    deal_arm_table table;
    table.version = DEAL_ARM_TABLE_VERSION;
    table.count = 0;
    table.records = NULL;

    deal_origin origin;
    origin.source_file = "probe/main.deal";
    origin.line = 1;
    origin.column = 1;

    deal_outcome error_only;
    error_only.error = NULL;
    deal_outcome_value boxed;
    boxed.error = NULL;
    boxed.value = result.value;
    deal_outcome_number number_outcome;
    number_outcome.error = NULL;
    number_outcome.value = 1.5;
    deal_outcome_boolean boolean_outcome;
    boolean_outcome.error = NULL;
    boolean_outcome.value = 0U;
    deal_outcome_string string_outcome;
    string_outcome.error = NULL;
    string_outcome.value = NULL;
    deal_outcome_reference reference_outcome;
    reference_outcome.error = NULL;
    reference_outcome.value = NULL;

    deal_gc_statistics_record statistics;
    statistics.live_bytes = 0;
    statistics.object_count = 0;
    statistics.allocated_bytes = 0;
    statistics.collections = 0;
    statistics.swept_objects = 0;

    /* The count-zero sentinel shape: one entry, never looked up. */
    if (deal_host_registry_count != 0) {
        printf("abi sentinel failed\n");
        return 1;
    }
    if (deal_host_registry[0].specifier != NULL
            || deal_host_registry[0].module != NULL) {
        printf("abi sentinel entry failed\n");
        return 1;
    }


    if (completion.kind != DEAL_COMPLETION_UNDEFINED
            || span.length != 0
            || declared.export_count != 1
            || binding.export_count != 0
            || table.count != 0
            || origin.line != 1
            || boxed.value.generation != 7U
            || number_outcome.value != 1.5
            || boolean_outcome.value != 0U
            || string_outcome.value != NULL
            || reference_outcome.value != NULL
            || error_only.error != NULL
            || statistics.collections != 0
            || relayed.kind != DEAL_FAILURE_DEAL_ERROR
            || result.failure.kind != DEAL_FAILURE_HOST_TEXT
            || host_text.error != 0U
            || relayed.error != 42U) {
        printf("abi record construction failed\n");
        return 1;
    }

    printf("abi ok\n");
    return 0;
}
